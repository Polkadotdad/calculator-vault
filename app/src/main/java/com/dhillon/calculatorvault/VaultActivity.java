package com.dhillon.calculatorvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The hidden vault: lists hidden photos, videos and files, and imports / exports them. */
public class VaultActivity extends Activity {

    static volatile boolean lockNow;

    private static final int REQ_MEDIA = 1;
    private static final int REQ_FILES = 2;
    private static final int REQ_EXPORT = 3;

    private static final int MENU_CHANGE_CODE = 10;
    private static final int MENU_RECOVERY = 11;
    private static final int MENU_HELP = 13;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 48L * 1024 * 1024)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private VaultStore store;
    private final List<VaultStore.Item> items = new ArrayList<>();
    private ItemAdapter adapter;
    private TextView empty;
    private TextView titleView;
    private boolean internalNav;
    private VaultStore.Item pendingExport;
    private int thumbPx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setTitle("Vault");
        store = VaultStore.get(this);
        thumbPx = getResources().getDisplayMetrics().widthPixels / 3;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.vault_primary));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int tp = Dialogs.dp(this, 12);
        top.setPadding(Dialogs.dp(this, 16), tp, Dialogs.dp(this, 4), tp);
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        top.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(topButton("Lock", v -> finish()));
        TextView more = topButton("⋮", null);
        more.setTextSize(22);
        more.setOnClickListener(v -> showMoreMenu(more));
        top.addView(more);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(this);
        body.setBackgroundColor(getColor(R.color.vault_bg));
        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        int gap = Dialogs.dp(this, 3);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setPadding(gap, gap, gap, gap);
        grid.setClipToPadding(false);
        adapter = new ItemAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> openItem(items.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            itemMenu(items.get(pos));
            return true;
        });
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        empty = new TextView(this);
        empty.setText("Your vault is empty.\n\nTap a button below to hide photos, videos or files.\n"
                + "They are encrypted and kept only inside this app.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(16);
        empty.setTextColor(Color.DKGRAY);
        int p = Dialogs.dp(this, 32);
        empty.setPadding(p, p, p, p);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int bp = Dialogs.dp(this, 8);
        bar.setPadding(bp, bp, bp, bp);
        bar.setBackgroundColor(Color.WHITE);
        bar.addView(barButton("+ Photos & videos", v -> pick(REQ_MEDIA)), barParams());
        bar.addView(barButton("+ Files", v -> pick(REQ_FILES)), barParams());
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        UiUtil.padForSystemBars(root);
        reload();
    }

    private TextView topButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        int p = Dialogs.dp(this, 12);
        b.setPadding(p, Dialogs.dp(this, 6), p, Dialogs.dp(this, 6));
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            b.setBackgroundResource(tv.resourceId);
        }
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private void showMoreMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu menu = pm.getMenu();
        menu.add(0, MENU_CHANGE_CODE, 0, "Change secret code");
        menu.add(0, MENU_RECOVERY, 1, "Change recovery question");
        menu.add(0, MENU_HELP, 2, "How to use");
        pm.setOnMenuItemClickListener(this::onMenuItem);
        pm.show();
    }

    private Button barButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.vault_primary));
        bg.setCornerRadius(Dialogs.dp(this, 12));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        int m = Dialogs.dp(this, 4);
        b.setPadding(m, Dialogs.dp(this, 12), m, Dialogs.dp(this, 12));
        return b;
    }

    private LinearLayout.LayoutParams barParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        int m = Dialogs.dp(this, 4);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }

    @Override
    protected void onResume() {
        super.onResume();
        internalNav = false;
        if (lockNow) {
            lockNow = false;
            finish();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the vault (home button, screen off, switching apps) locks it.
        if (!internalNav && !isChangingConfigurations()) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
        thumbs.shutdownNow();
    }

    private boolean onMenuItem(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_CHANGE_CODE:
                Dialogs.setupCode(this, false, false, null);
                return true;
            case MENU_RECOVERY:
                Dialogs.changeRecovery(this);
                return true;
            case MENU_HELP:
                new AlertDialog.Builder(this)
                        .setTitle("How to use")
                        .setMessage("• Open the vault: type your secret code on the calculator, then press +\n\n"
                                + "• Hide items: tap + Photos & videos or + Files. You can then remove the originals "
                                + "from your phone.\n\n"
                                + "• Tap an item to view it. Press and hold an item to unhide (save a normal copy back "
                                + "to your phone) or delete it.\n\n"
                                + "• Forgot your code? On the calculator, press and hold  =  and answer your recovery "
                                + "question.\n\n"
                                + "• Important: hidden items live only inside this app. Uninstalling the app or "
                                + "clearing its data deletes them for good, and they are not included in phone "
                                + "backups. Unhide anything you want to keep before changing phones.")
                        .setPositiveButton("OK", null)
                        .show();
                return true;
            default:
                return false;
        }
    }

    // ---------- import ----------

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (req == REQ_MEDIA) {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        } else {
            i.setType("*/*");
        }
        internalNav = true;
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            internalNav = false;
            Toast.makeText(this, "No file picker found on this phone", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            pendingExport = null;
            return;
        }
        if (requestCode == REQ_EXPORT) {
            if (pendingExport != null && data.getData() != null) exportTo(pendingExport, data.getData());
            pendingExport = null;
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) {
                Uri u = clip.getItemAt(k).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) importAll(uris);
    }

    private AlertDialog progress(String msg) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        int p = Dialogs.dp(this, 20);
        l.setPadding(p, p, p, p);
        l.addView(new ProgressBar(this));
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextSize(16);
        t.setPadding(p, 0, 0, 0);
        t.setId(android.R.id.message);
        l.addView(t);
        AlertDialog d = new AlertDialog.Builder(this).setView(l).setCancelable(false).create();
        d.show();
        return d;
    }

    private void importAll(List<Uri> uris) {
        AlertDialog dlg = progress("Hiding 1 of " + uris.size() + "…");
        ContentResolver cr = getContentResolver();
        io.execute(() -> {
            List<Uri> done = new ArrayList<>();
            int failed = 0;
            for (int k = 0; k < uris.size(); k++) {
                final int idx = k + 1;
                ui.post(() -> {
                    TextView t = dlg.findViewById(android.R.id.message);
                    if (t != null) t.setText("Hiding " + idx + " of " + uris.size() + "…");
                });
                Uri u = uris.get(k);
                try (InputStream in = cr.openInputStream(u)) {
                    if (in == null) throw new IllegalStateException("cannot open");
                    store.add(in, displayName(cr, u), cr.getType(u));
                    done.add(u);
                } catch (Exception e) {
                    failed++;
                }
            }
            final int fails = failed;
            ui.post(() -> {
                if (isDestroyed()) return;
                dlg.dismiss();
                reload();
                afterImport(done, fails);
            });
        });
    }

    private void afterImport(List<Uri> done, int failed) {
        String msg = done.size() + (done.size() == 1 ? " item" : " items") + " hidden in your vault.";
        if (failed > 0) msg += "\n" + failed + " could not be read and were skipped.";
        if (done.isEmpty()) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Hidden")
                .setMessage(msg + "\n\nRemove the original copies from your phone so they no longer show in "
                        + "your gallery or files?")
                .setPositiveButton("Remove originals", (d, w) -> deleteOriginals(done))
                .setNegativeButton("Keep them", null)
                .show();
    }

    private void deleteOriginals(List<Uri> uris) {
        ContentResolver cr = getContentResolver();
        io.execute(() -> {
            int ok = 0;
            for (Uri u : uris) {
                try {
                    if (DocumentsContract.deleteDocument(cr, u)) ok++;
                } catch (Exception ignored) {
                }
            }
            final int removed = ok;
            ui.post(() -> {
                if (isDestroyed()) return;
                if (removed == uris.size()) {
                    Toast.makeText(this, "Originals removed", Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(this)
                            .setMessage("Removed " + removed + " of " + uris.size() + " originals. Your phone did not "
                                    + "allow the rest to be removed automatically. They are safely in the vault, "
                                    + "so you can delete them yourself from your Gallery or Files app.")
                            .setPositiveButton("OK", null).show();
                }
            });
        });
    }

    private static String displayName(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? "file" : last;
    }

    // ---------- items ----------

    private void reload() {
        items.clear();
        items.addAll(store.items());
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        titleView.setText(items.isEmpty() ? "Vault" : "Vault (" + items.size() + ")");
    }

    private void openItem(VaultStore.Item it) {
        if (it.isImage() || it.isVideo()) {
            internalNav = true;
            startActivity(new Intent(this, ViewerActivity.class).putExtra("id", it.id));
        } else {
            itemMenu(it);
        }
    }

    private void itemMenu(VaultStore.Item it) {
        String[] opts = (it.isImage() || it.isVideo())
                ? new String[]{"View", "Unhide (save copy to phone)", "Delete permanently"}
                : new String[]{"Unhide (save copy to phone)", "Delete permanently"};
        new AlertDialog.Builder(this)
                .setTitle(it.name + "\n" + humanSize(it.size))
                .setItems(opts, (d, w) -> {
                    String o = opts[w];
                    if (o.startsWith("View")) openItem(it);
                    else if (o.startsWith("Unhide")) startExport(it);
                    else confirmDelete(it);
                })
                .show();
    }

    private void confirmDelete(VaultStore.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("Delete permanently?")
                .setMessage(it.name + " will be erased from the vault. This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    store.remove(it);
                    cache.remove(it.id);
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startExport(VaultStore.Item it) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(it.mime);
        i.putExtra(Intent.EXTRA_TITLE, it.name);
        pendingExport = it;
        internalNav = true;
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            internalNav = false;
            pendingExport = null;
            Toast.makeText(this, "No file picker found on this phone", Toast.LENGTH_LONG).show();
        }
    }

    private void exportTo(VaultStore.Item it, Uri dest) {
        AlertDialog dlg = progress("Saving copy…");
        ContentResolver cr = getContentResolver();
        io.execute(() -> {
            boolean ok = false;
            try (InputStream in = store.open(it); OutputStream out = cr.openOutputStream(dest)) {
                if (out != null) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    ok = true;
                }
            } catch (Exception ignored) {
            }
            final boolean success = ok;
            ui.post(() -> {
                if (isDestroyed()) return;
                dlg.dismiss();
                if (!success) {
                    Toast.makeText(this, "Could not save the copy", Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setMessage("A normal copy of " + it.name + " was saved to your phone.\n\nRemove it from the vault too?")
                        .setPositiveButton("Remove from vault", (d, w) -> {
                            store.remove(it);
                            cache.remove(it.id);
                            reload();
                        })
                        .setNegativeButton("Keep in vault", null)
                        .show();
            });
        });
    }

    static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    // ---------- thumbnails ----------

    static Bitmap decodeImage(Context c, VaultStore store, VaultStore.Item it, int reqPx) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = store.open(it)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqPx && o.outHeight / (sample * 2) >= reqPx) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = store.open(it)) {
            bm = BitmapFactory.decodeStream(in, null, o2);
        }
        if (bm == null) return null;
        int rotation = 0;
        try (InputStream in = store.open(it)) {
            int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) {
        }
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            bm = r;
        }
        return bm;
    }

    static Bitmap videoFrame(Context c, VaultStore store, VaultStore.Item it, int reqPx) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        VaultCrypto.EncryptedDataSource ds = null;
        try {
            ds = new VaultCrypto.EncryptedDataSource(c, store.fileFor(it));
            r.setDataSource(ds);
            Bitmap f = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (f == null) return null;
            float scale = Math.min(1f, reqPx / (float) Math.min(f.getWidth(), f.getHeight()));
            if (scale < 1f) {
                Bitmap s = Bitmap.createScaledBitmap(f, Math.max(1, (int) (f.getWidth() * scale)),
                        Math.max(1, (int) (f.getHeight() * scale)), true);
                if (s != f) f.recycle();
                return s;
            }
            return f;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
            if (ds != null) {
                try {
                    ds.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private final class ItemAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Tile t = convert instanceof Tile ? (Tile) convert : new Tile(VaultActivity.this);
            VaultStore.Item it = items.get(position);
            t.bind(it);
            return t;
        }
    }

    private final class Tile extends FrameLayout {
        final ImageView image;
        final TextView label;
        final TextView badge;
        String boundId;

        Tile(Context c) {
            super(c);
            setBackgroundColor(0xFFDDE3EA);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image, new LayoutParams(-1, -1));
            label = new TextView(c);
            label.setTextColor(Color.WHITE);
            label.setTextSize(11);
            label.setMaxLines(2);
            label.setBackgroundColor(0x99000000);
            int p = Dialogs.dp(VaultActivity.this, 4);
            label.setPadding(p, p, p, p);
            addView(label, new LayoutParams(-1, -2, Gravity.BOTTOM));
            badge = new TextView(c);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(28);
            badge.setGravity(Gravity.CENTER);
            addView(badge, new LayoutParams(-1, -1));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }

        void bind(VaultStore.Item it) {
            boundId = it.id;
            image.setImageBitmap(null);
            if (it.isImage()) {
                label.setVisibility(GONE);
                badge.setText("");
            } else if (it.isVideo()) {
                label.setVisibility(VISIBLE);
                label.setText(it.name);
                badge.setText("▶");
            } else {
                label.setVisibility(VISIBLE);
                label.setText(it.name);
                badge.setTextColor(0xFF1E3A8A);
                badge.setText(extension(it.name));
                badge.setTextSize(20);
                return;
            }
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(28);
            Bitmap cached = cache.get(it.id);
            if (cached != null) {
                image.setImageBitmap(cached);
                return;
            }
            final String id = it.id;
            thumbs.execute(() -> {
                Bitmap bm = null;
                try {
                    bm = it.isImage() ? decodeImage(VaultActivity.this, store, it, thumbPx)
                            : videoFrame(VaultActivity.this, store, it, thumbPx);
                } catch (Throwable ignored) {
                }
                final Bitmap result = bm;
                if (result != null) cache.put(id, result);
                ui.post(() -> {
                    if (id.equals(boundId) && result != null) image.setImageBitmap(result);
                });
            });
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "FILE";
        String e = name.substring(dot + 1).toUpperCase(Locale.ROOT);
        return e.length() > 4 ? e.substring(0, 4) : e;
    }
}
