package com.example.services;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 1;
    private static final int S_IDLE = 0, S_SCAN = 1, S_RESULT = 2, S_CLEAN = 3, S_DONE = 4;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content, bottom;
    private ArrayList<Scanner.Category> cats = new ArrayList<Scanner.Category>();
    private int state = S_IDLE;
    private long freed = 0;
    private Button cleanBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        sv.addView(content);

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);

        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        render();
    }

    // ---------- екрани ----------

    private void render() {
        content.removeAllViews();
        bottom.removeAllViews();
        cleanBtn = null;

        content.addView(text("Чистильник файлів", 22, true));

        switch (state) {
            case S_IDLE:
                content.addView(text("Знаходить на накопичувачі файли, які можна видалити: логи, "
                        + "тимчасові файли, мініатюри, кеш програм та інше. Спочатку сканує, потім "
                        + "показує список, а ти сам вибираєш, що очистити.", 14, false));
                bottom.addView(button("Сканувати", new View.OnClickListener() {
                    public void onClick(View v) {
                        startScan();
                    }
                }));
                break;

            case S_SCAN:
                content.addView(progress("Сканування…"));
                break;

            case S_RESULT:
                if (cats.isEmpty()) {
                    content.addView(text("Нічого зайвого не знайдено.", 15, false));
                    bottom.addView(button("Сканувати знову", new View.OnClickListener() {
                        public void onClick(View v) {
                            startScan();
                        }
                    }));
                } else {
                    long all = 0;
                    for (Scanner.Category c : cats) all += c.total;
                    content.addView(text("Знайдено, що можна очистити: " + Scanner.fmt(all), 14, false));
                    for (Scanner.Category c : cats) {
                        content.addView(divider());
                        content.addView(categoryView(c));
                    }
                    cleanBtn = button("Очистити", new View.OnClickListener() {
                        public void onClick(View v) {
                            confirmClean();
                        }
                    });
                    bottom.addView(cleanBtn);
                    updateButton();
                }
                break;

            case S_CLEAN:
                content.addView(progress("Очищення…"));
                break;

            case S_DONE:
                content.addView(text("Готово. Звільнено ≈ " + Scanner.fmt(freed), 16, true));
                bottom.addView(button("Сканувати знову", new View.OnClickListener() {
                    public void onClick(View v) {
                        startScan();
                    }
                }));
                break;
        }
    }

    private View categoryView(final Scanner.Category c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        CheckBox cb = new CheckBox(this);
        cb.setText(c.title);
        cb.setTextSize(16);
        cb.setChecked(c.checked);
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                c.checked = checked;
                updateButton();
            }
        });
        head.addView(cb, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(text(Scanner.fmt(c.total), 15, true));
        box.addView(head);

        TextView hint = text(c.hint + "\nЕлементів: " + c.items.size(), 12, false);
        hint.setTextColor(0xFF666666);
        box.addView(hint);

        final LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);

        final TextView toggle = text("Показати список ▾", 13, false);
        toggle.setTextColor(0xFF1565C0);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                c.expanded = !c.expanded;
                fillDetails(c, details);
                ((TextView) v).setText(c.expanded ? "Сховати список ▴" : "Показати список ▾");
            }
        });
        box.addView(toggle);
        box.addView(details);
        return box;
    }

    private void fillDetails(Scanner.Category c, LinearLayout details) {
        details.removeAllViews();
        if (!c.expanded) return;
        int n = Math.min(30, c.items.size());
        for (int i = 0; i < n; i++) {
            Scanner.Item it = c.items.get(i);
            TextView t = text(rel(it.file.getAbsolutePath()) + "  ·  " + Scanner.fmt(it.size), 11, false);
            t.setTextColor(0xFF444444);
            t.setPadding(dp(16), dp(1), 0, dp(1));
            details.addView(t);
        }
        if (c.items.size() > n) {
            TextView t = text("… і ще " + (c.items.size() - n), 11, false);
            t.setPadding(dp(16), dp(1), 0, dp(1));
            details.addView(t);
        }
    }

    private void updateButton() {
        if (cleanBtn == null) return;
        long bytes = 0;
        int count = 0;
        for (Scanner.Category c : cats) {
            if (!c.checked) continue;
            bytes += c.total;
            count += c.items.size();
        }
        cleanBtn.setEnabled(count > 0);
        cleanBtn.setText(count > 0 ? "Очистити (" + Scanner.fmt(bytes) + ")" : "Нічого не вибрано");
    }

    // ---------- дії ----------

    private void startScan() {
        if (!hasAccess()) {
            requestAccess();
            return;
        }
        state = S_SCAN;
        render();
        new Thread(new Runnable() {
            public void run() {
                ArrayList<Scanner.Category> found;
                try {
                    found = Scanner.scan();
                } catch (Throwable e) {
                    found = new ArrayList<Scanner.Category>();
                }
                final ArrayList<Scanner.Category> result = found;
                ui.post(new Runnable() {
                    public void run() {
                        cats = result;
                        state = S_RESULT;
                        render();
                    }
                });
            }
        }).start();
    }

    private void confirmClean() {
        long bytes = 0;
        int count = 0;
        for (Scanner.Category c : cats) {
            if (!c.checked) continue;
            bytes += c.total;
            count += c.items.size();
        }
        if (count == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Очистити вибране?")
                .setMessage("Буде видалено елементів: " + count + " (≈ " + Scanner.fmt(bytes)
                        + "). Це не можна скасувати.")
                .setNegativeButton("Скасувати", null)
                .setPositiveButton("Очистити", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        doClean();
                    }
                })
                .show();
    }

    private void doClean() {
        state = S_CLEAN;
        render();
        final ArrayList<Scanner.Category> snapshot = cats;
        new Thread(new Runnable() {
            public void run() {
                final long f = Scanner.clean(snapshot);
                ui.post(new Runnable() {
                    public void run() {
                        freed = f;
                        state = S_DONE;
                        render();
                    }
                });
            }
        }).start();
    }

    // ---------- доступ до файлів ----------

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "Дозволь доступ до всіх файлів і натисни «Сканувати» ще раз",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_STORAGE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        }
    }

    // ---------- допоміжні ----------

    private String rel(String path) {
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        return path.startsWith(root) ? path.substring(root.length()) : path;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        return b;
    }

    private View progress(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(48), 0, 0);
        box.addView(new ProgressBar(this));
        TextView t = text(label, 16, false);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        return box;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x22000000);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }
}
