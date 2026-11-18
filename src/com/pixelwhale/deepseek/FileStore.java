package com.pixelwhale.deepseek;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/** Private copies of documents selected through the Android system picker. */
final class FileStore {
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static File root;

    static void init(Context context) {
        root = new File(context.getApplicationContext().getFilesDir(), "documents");
    }

    static String save(Context context, Uri uri) throws Exception {
        init(context);
        String name = "document";
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        if (name == null || name.trim().isEmpty()) name = "document";
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.length() > 80) name = name.substring(name.length() - 80);
        if (!root.isDirectory() && !root.mkdirs()) throw new Exception("无法保存文档。");
        File target = new File(root, UUID.randomUUID() + "__" + name);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new Exception("无法读取文档。");
            byte[] buffer = new byte[8192];
            long size = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                size += count;
                if (size > MAX_BYTES) throw new Exception("文档不能超过 32 MB。");
                out.write(buffer, 0, count);
            }
            if (size == 0) throw new Exception("文档为空。");
            return target.getAbsolutePath();
        } catch (Exception error) {
            target.delete();
            throw error;
        }
    }

    static String name(String path) {
        String name = new File(path).getName();
        int separator = name.indexOf("__");
        return separator >= 0 ? name.substring(separator + 2) : name;
    }

    static void delete(String path) {
        if (path == null || root == null) return;
        try {
            File file = new File(path);
            if (file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) file.delete();
        } catch (Exception ignored) { }
    }
}
