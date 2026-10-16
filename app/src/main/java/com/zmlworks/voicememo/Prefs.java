package com.zmlworks.voicememo;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

/** User settings + the settings dialog. */
final class Prefs {
    private Prefs() {}

    static final float MIN_SP = 12, MAX_SP = 64, DEFAULT_SP = 24;

    static float clampSize(float sp) { return Math.max(MIN_SP, Math.min(MAX_SP, Math.round(sp))); }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("prefs", Context.MODE_PRIVATE); }

    static float textSize(Context c) { return clampSize(sp(c).getFloat("textSize", DEFAULT_SP)); }
    static void setTextSize(Context c, float v) { sp(c).edit().putFloat("textSize", clampSize(v)).apply(); }
    /** true = continuous recording (long-press starts, tap/停止 stops); false = press-and-hold-to-talk. */
    static boolean continuous(Context c) { return sp(c).getBoolean("continuous", false); }
    static boolean preferOffline(Context c) { return sp(c).getBoolean("preferOffline", true); }

    static void showDialog(Context c, Runnable onDone) {
        int pad = Ui.dp(c, 20);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView sizeLabel = new TextView(c);
        SeekBar seek = new SeekBar(c);
        seek.setMax((int) (MAX_SP - MIN_SP));
        seek.setProgress((int) (textSize(c) - MIN_SP));
        sizeLabel.setText(c.getString(R.string.set_size, (int) textSize(c)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                sizeLabel.setText(c.getString(R.string.set_size, (int) (p + MIN_SP)));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(sizeLabel);
        box.addView(seek);

        TextView modeLabel = new TextView(c);
        modeLabel.setText(R.string.set_mode);
        modeLabel.setPadding(0, pad / 2, 0, 0);
        box.addView(modeLabel);
        RadioGroup rg = new RadioGroup(c);
        RadioButton hold = new RadioButton(c);
        hold.setText(R.string.set_mode_hold);
        hold.setId(android.R.id.button1);
        RadioButton cont = new RadioButton(c);
        cont.setText(R.string.set_mode_continuous);
        cont.setId(android.R.id.button2);
        rg.addView(hold);
        rg.addView(cont);
        rg.check(continuous(c) ? cont.getId() : hold.getId());
        box.addView(rg);

        CheckBox offline = new CheckBox(c);
        offline.setText(R.string.set_offline);
        offline.setChecked(preferOffline(c));
        box.addView(offline);

        TextView note = new TextView(c);
        note.setText(R.string.set_note);
        note.setTextColor(Ui.DIM);
        note.setTextSize(12);
        note.setPadding(0, pad / 2, 0, 0);
        box.addView(note);

        new AlertDialog.Builder(c)
                .setTitle(R.string.settings)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    sp(c).edit()
                            .putFloat("textSize", seek.getProgress() + MIN_SP)
                            .putBoolean("continuous", rg.getCheckedRadioButtonId() == cont.getId())
                            .putBoolean("preferOffline", offline.isChecked())
                            .apply();
                    if (onDone != null) onDone.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
