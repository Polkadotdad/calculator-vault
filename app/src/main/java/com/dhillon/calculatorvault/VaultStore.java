package com.dhillon.calculatorvault;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Keeps the list of hidden items (name, type, size) and their encrypted files in private storage. */
final class VaultStore {

    static final class Item {
        String id;
        String name;
        String mime;
        long size;
        long added;

        boolean isImage() {
            return mime != null && mime.startsWith("image/");
        }

        boolean isVideo() {
            return mime != null && mime.startsWith("video/");
        }
    }

    private static VaultStore instance;

    private final Context app;
    private final File dir;
    private final File index;
    private final List<Item> items = new ArrayList<>();

    static synchronized VaultStore get(Context c) {
        if (instance == null) instance = new VaultStore(c.getApplicationContext());
        return instance;
    }

    private VaultStore(Context app) {
        this.app = app;
        dir = new File(app.getFilesDir(), "vault");
        if (!dir.exists()) dir.mkdirs();
        index = new File(dir, "index.json");
        load();
    }

    synchronized List<Item> items() {
        return new ArrayList<>(items);
    }

    synchronized Item find(String id) {
        for (Item i : items) if (i.id.equals(id)) return i;
        return null;
    }

    File fileFor(Item i) {
        return new File(dir, i.id + ".bin");
    }

    InputStream open(Item i) throws Exception {
        return VaultCrypto.decrypt(app, fileFor(i));
    }

    /** Encrypts the stream into the vault and records it. Newest items are listed first. */
    Item add(InputStream in, String name, String mime) throws Exception {
        Item it = new Item();
        it.id = UUID.randomUUID().toString();
        it.name = name == null || name.isEmpty() ? "file" : name;
        it.mime = mime == null ? "application/octet-stream" : mime;
        it.added = System.currentTimeMillis();
        File f = fileFor(it);
        try {
            it.size = VaultCrypto.encrypt(app, in, f);
        } catch (Exception e) {
            f.delete();
            throw e;
        }
        synchronized (this) {
            items.add(0, it);
            save();
        }
        return it;
    }

    synchronized void remove(Item it) {
        items.remove(find(it.id));
        fileFor(it).delete();
        save();
    }

    private void load() {
        items.clear();
        if (!index.exists()) return;
        try (FileInputStream fis = new FileInputStream(index)) {
            byte[] data = new byte[(int) index.length()];
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            JSONArray arr = new JSONArray(new String(data, 0, off, StandardCharsets.UTF_8));
            for (int k = 0; k < arr.length(); k++) {
                JSONObject o = arr.getJSONObject(k);
                Item it = new Item();
                it.id = o.getString("id");
                it.name = o.optString("name", "file");
                it.mime = o.optString("mime", "application/octet-stream");
                it.size = o.optLong("size", 0);
                it.added = o.optLong("added", 0);
                if (fileFor(it).exists()) items.add(it);
            }
        } catch (Exception ignored) {
            // A damaged index leaves an empty list rather than crashing the app.
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("name", it.name);
                o.put("mime", it.mime);
                o.put("size", it.size);
                o.put("added", it.added);
                arr.put(o);
            }
            File tmp = new File(dir, "index.json.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            if (!tmp.renameTo(index)) {
                index.delete();
                tmp.renameTo(index);
            }
        } catch (Exception ignored) {
        }
    }
}
