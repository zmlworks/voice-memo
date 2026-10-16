package com.zmlworks.voicememo;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Japanese speech recognition session on top of android.speech.SpeechRecognizer.
 * keepGoing = restart after every result (hold-to-talk while held, or continuous mode) until stop().
 * Falls back between offline/online once if the preferred mode is unavailable.
 */
final class Speech implements RecognitionListener {
    interface Callback {
        void onSpeechState(boolean active, boolean listening, String status);
        void onPartial(String text);
        void onFinal(String text);
        void onSpeechError(String message);
    }

    private final Context ctx;
    private final Callback cb;
    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private boolean active, keepGoing, stopping, preferOffline, triedFallback, between;
    private int emptyRuns;
    private final Runnable restart = this::listen;

    private void scheduleRestart(long ms) {
        between = true;
        h.postDelayed(restart, ms);
    }
    private final Runnable finishTimeout = () -> { if (active && stopping) end(null); };

    Speech(Context ctx, Callback cb) { this.ctx = ctx; this.cb = cb; }

    static boolean serviceAvailable(Context c) {
        try { return SpeechRecognizer.isRecognitionAvailable(c); } catch (RuntimeException e) { return false; }
    }

    static Intent recognizerIntent(boolean preferOffline) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (preferOffline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        return i;
    }

    boolean isActive() { return active; }

    void start(boolean keepGoing, boolean preferOffline) {
        if (active) return;
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx);
            sr.setRecognitionListener(this);
        }
        this.active = true;
        this.keepGoing = keepGoing;
        this.stopping = false;
        this.preferOffline = preferOffline;
        this.triedFallback = false;
        this.emptyRuns = 0;
        listen();
    }

    private void listen() {
        between = false;
        if (!active || sr == null) return;
        try {
            sr.cancel();
            sr.startListening(recognizerIntent(preferOffline));
            cb.onSpeechState(true, true, ctx.getString(R.string.rec_listening));
        } catch (RuntimeException e) {
            end(ctx.getString(R.string.err_client));
        }
    }

    /** Stop listening; the result of the current utterance still arrives (onFinal) before the session ends. */
    void stop() {
        if (!active) return;
        keepGoing = false;
        h.removeCallbacks(restart);
        if (between) { end(null); return; } // released between two utterances: nothing pending
        if (stopping) return;
        stopping = true;
        try { sr.stopListening(); } catch (RuntimeException ignored) { /* already stopped */ }
        cb.onSpeechState(true, false, ctx.getString(R.string.rec_finishing));
        h.postDelayed(finishTimeout, 6000);
    }

    void cancel() {
        between = false;
        stopping = false;
        h.removeCallbacks(restart);
        h.removeCallbacks(finishTimeout);
        if (sr != null) try { sr.cancel(); } catch (RuntimeException ignored) { /* ignore */ }
        if (active) { active = false; cb.onPartial(""); cb.onSpeechState(false, false, ""); }
    }

    void destroy() {
        cancel();
        if (sr != null) { try { sr.destroy(); } catch (RuntimeException ignored) { /* ignore */ } sr = null; }
    }

    private void end(String err) {
        h.removeCallbacks(restart);
        h.removeCallbacks(finishTimeout);
        between = false;
        boolean was = active;
        active = false;
        stopping = false;
        cb.onPartial("");
        if (was) cb.onSpeechState(false, false, "");
        if (err != null) cb.onSpeechError(err);
    }

    private static String best(Bundle b) {
        if (b == null) return "";
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() || r.get(0) == null ? "" : r.get(0);
    }

    // ------------------------------------------------------------ RecognitionListener

    @Override public void onReadyForSpeech(Bundle params) {
        if (active && !stopping) cb.onSpeechState(true, true, ctx.getString(R.string.rec_speak));
    }
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {
        if (active && !keepGoing) cb.onSpeechState(true, false, ctx.getString(R.string.rec_finishing));
    }
    @Override public void onEvent(int eventType, Bundle params) {}

    @Override public void onPartialResults(Bundle partial) {
        if (!active) return;
        String s = best(partial);
        if (!s.isEmpty()) cb.onPartial(s);
    }

    @Override public void onResults(Bundle results) {
        if (!active) return;
        String s = best(results);
        cb.onPartial("");
        if (!s.isEmpty()) { cb.onFinal(s); emptyRuns = 0; }
        if (keepGoing && !stopping) scheduleRestart(120);
        else end(null);
    }

    @Override public void onError(int error) {
        if (!active) return;
        boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        if (stopping) { // user ended the session: silence / client errors after stopListening are expected
            end(silence || error == SpeechRecognizer.ERROR_CLIENT ? null : message(error));
            return;
        }
        if (!keepGoing && silence) { end(message(error)); return; }
        if (keepGoing && silence) {
            if (++emptyRuns >= 8) { end(ctx.getString(R.string.err_idle)); return; }
            scheduleRestart(150);
            return;
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) { scheduleRestart(400); return; }
        // offline language pack missing -> try online once; network trouble -> try offline once
        boolean langUnavailable = error == 12 /* ERROR_LANGUAGE_NOT_SUPPORTED */ || error == 13 /* ERROR_LANGUAGE_UNAVAILABLE */;
        boolean network = error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER || error == 11 /* ERROR_SERVER_DISCONNECTED */;
        if (!triedFallback && ((langUnavailable && preferOffline) || (network && !preferOffline))) {
            triedFallback = true;
            preferOffline = !preferOffline;
            scheduleRestart(150);
            return;
        }
        end(message(error));
    }

    private String message(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
            case 11:
                return ctx.getString(R.string.err_network);
            case SpeechRecognizer.ERROR_AUDIO: return ctx.getString(R.string.err_audio);
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return ctx.getString(R.string.err_permission);
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return ctx.getString(R.string.err_busy);
            case 10: return ctx.getString(R.string.err_busy);
            case 12: case 13: return ctx.getString(R.string.err_language);
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return ctx.getString(R.string.err_nomatch);
            default: return ctx.getString(R.string.err_client) + " (" + error + ")";
        }
    }
}
