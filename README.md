# 縦書き音声メモ (voice-memo) 0.1.0

Android (minSdk 26, targetSdk 35, plain Views, no dependencies) memo app that shows text in Japanese vertical
writing and takes Japanese voice input.

## Use
- 一覧: `＋ 新規` creates a memo; tap opens; long-press → 開く / 共有 / 削除 (with confirmation). Autosaved
  (0.8 s after each change and on leaving). Title = first non-blank line. Empty memos are discarded on close.
- Editor (vertical text, columns top→bottom, flowing right→left):
  - tap = caret · **long-press = voice input** at the caret · horizontal swipe = scroll (fling) ·
    swipe that starts vertically = select (from the start char to the char under the finger, across columns;
    auto-scrolls at the left/right edge) · diagonal start = nothing · pinch = text size.
  - Direction is decided after the first 14 dp: ≤30° from horizontal = scroll, ≤30° from vertical = select, else no-op.
  - Selection bar: コピー / 切り取り / 削除 / 共有 / 全選択 / ✕.
  - Top bar: ← 一覧 · 🎤 音声 (one utterance; tap again to stop) · ↶ 戻す (undo, 100 steps incl. every voice insertion)
    · ⌨ 横書き (normal EditText for keyboard editing; 縦書き表示 or Back returns) · 共有 · 設定 · 削除.
- Voice: default **press-and-hold**: long-press, keep holding and talk, release to stop (pauses while holding restart
  recognition automatically). Settings → 連続録音: long-press starts, tap anywhere / ■ 停止 ends (auto-stop after ~8
  silent rounds). Red bar = recording; live partial text is shown in blue at the caret. ja-JP, EXTRA_PREFER_OFFLINE
  (setting, default on; falls back to online once if the offline pack is missing, and vice versa on network errors).
  If no SpeechRecognizer service exists, the RecognizerIntent dialog is used instead.
- Settings: text size 12–64 sp, voice mode, prefer offline.

## Permissions
RECORD_AUDIO only (asked at the first voice input). No INTERNET; the system speech service (usually the Google app)
does recognition. `<queries>` for RecognitionService / RECOGNIZE_SPEECH (Android 11+ package visibility).

## Build
`. ../env.sh && ./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease`
Release signing reads /workspace/android/keys/keystore.properties (or env ZML_KEYSTORE_PROPS); nothing secret is in the repo.
