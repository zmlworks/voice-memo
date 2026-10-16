package com.zmlworks.voicememo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Memo list: new / open / share / delete (with confirmation). */
public class MainActivity extends Activity {
    private MemoStore store;
    private final List<MemoStore.Memo> memos = new ArrayList<>();
    private Adapter adapter;
    private TextView empty;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = MemoStore.of(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 12);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(p, p / 2, p, p / 2);
        top.setBackgroundColor(Ui.BG2);
        TextView title = new TextView(this);
        title.setText(R.string.app_label);
        title.setTextSize(20);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        title.setTextColor(Ui.TEXT);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button add = new Button(this);
        add.setText(R.string.new_memo);
        add.setOnClickListener(v -> open(-1));
        top.addView(add);
        Button set = new Button(this);
        set.setText(R.string.settings);
        set.setOnClickListener(v -> Prefs.showDialog(this, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(this, 6));
        top.addView(set, lp);
        root.addView(top);

        empty = new TextView(this);
        empty.setText(R.string.list_empty);
        empty.setTextColor(Ui.DIM);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(p, p * 3, p, p);
        root.addView(empty);

        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Ui.GRID));
        list.setDividerHeight(Math.max(1, Ui.dp(this, 1)));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, rid) -> open(memos.get(pos).id));
        list.setOnItemLongClickListener((parent, view, pos, rid) -> { itemMenu(memos.get(pos)); return true; });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText(R.string.list_hint);
        hint.setTextColor(Ui.DIM);
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(p, p / 2, p, p / 2);
        root.addView(hint);

        setContentView(root);
        Ui.edgeToEdge(this, root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        memos.clear();
        memos.addAll(store.list());
        adapter.notifyDataSetChanged();
        empty.setVisibility(memos.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void open(long id) {
        Intent i = new Intent(this, EditorActivity.class);
        if (id >= 0) i.putExtra(EditorActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private String titleOf(MemoStore.Memo m) { return m.title.isEmpty() ? getString(R.string.untitled) : m.title; }

    private void itemMenu(MemoStore.Memo m) {
        String[] items = {getString(R.string.open), getString(R.string.share), getString(R.string.delete)};
        new AlertDialog.Builder(this)
                .setTitle(titleOf(m))
                .setItems(items, (d, w) -> {
                    if (w == 0) open(m.id);
                    else if (w == 1) share(store.load(m.id));
                    else confirmDelete(m);
                })
                .show();
    }

    private void confirmDelete(MemoStore.Memo m) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_memo_q)
                .setMessage(titleOf(m))
                .setPositiveButton(R.string.delete, (d, w) -> { store.delete(m.id); refresh(); })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void share(String s) {
        if (s == null || s.isEmpty()) { Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, s);
        try { startActivity(Intent.createChooser(i, getString(R.string.share))); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, R.string.no_share_app, Toast.LENGTH_SHORT).show(); }
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return memos.size(); }
        @Override public Object getItem(int i) { return memos.get(i); }
        @Override public long getItemId(int i) { return memos.get(i).id; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row;
            TextView t1, t2;
            if (cv instanceof LinearLayout) {
                row = (LinearLayout) cv;
                t1 = (TextView) row.getChildAt(0);
                t2 = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int p = Ui.dp(MainActivity.this, 14);
                row.setPadding(p, p * 2 / 3, p, p * 2 / 3);
                t1 = new TextView(MainActivity.this);
                t1.setTextSize(17);
                t1.setTextColor(Ui.TEXT);
                t1.setSingleLine(true);
                t1.setTypeface(Typeface.SERIF);
                t2 = new TextView(MainActivity.this);
                t2.setTextSize(12);
                t2.setTextColor(Ui.DIM);
                row.addView(t1);
                row.addView(t2);
            }
            MemoStore.Memo m = memos.get(pos);
            t1.setText(titleOf(m));
            t2.setText(getString(R.string.list_meta, fmt.format(new Date(m.modified)), m.length));
            return row;
        }
    }
}
