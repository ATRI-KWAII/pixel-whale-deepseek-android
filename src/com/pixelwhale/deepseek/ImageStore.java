package com.pixelwhale.deepseek;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Stores downsized JPEGs in private app storage, separate from the SQLite chat text. */
final class ImageStore {
    private static final int MAX_EDGE = 1536;
    private static final long MAX_FILE_BYTES = 1_500_000;
    private static volatile File imageRoot;

    static void init(Context context) {
        imageRoot = new File(context.getApplicationContext().getFilesDir(), "images");
    }

    static String saveFromUri(Context context, Uri uri) throws Exception {
        init(context);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取选择的图片。");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IOException("无法识别这张图片，请换一张试试。");
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE &&
                sample < 1024) sample *= 2;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取选择的图片。");
            decoded = BitmapFactory.decodeStream(input, null, options);
        } catch (OutOfMemoryError error) {
            throw new IOException("图片太大，请选择尺寸较小的图片。", error);
        }
        if (decoded == null) throw new IOException("图片解码失败，请换一张试试。");
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        float ratio = Math.min(1f, MAX_EDGE / (float) Math.max(width, height));
        int targetWidth = Math.max(1, Math.round(width * ratio));
        int targetHeight = Math.max(1, Math.round(height * ratio));
        Bitmap output;
        try {
            output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(decoded, null,
                    new android.graphics.Rect(0, 0, targetWidth, targetHeight), null);
        } catch (OutOfMemoryError error) {
            decoded.recycle();
            throw new IOException("图片太大，请选择尺寸较小的图片。", error);
        }
        decoded.recycle();
        File folder = imageRoot;
        if (!folder.isDirectory() && !folder.mkdirs()) {
            output.recycle();
            throw new IOException("无法创建图片存储目录。");
        }
        File destination = new File(folder, UUID.randomUUID().toString() + ".jpg");
        try {
            writeJpeg(output, destination, 84);
            if (destination.length() > MAX_FILE_BYTES) writeJpeg(output, destination, 65);
            if (destination.length() > MAX_FILE_BYTES) writeJpeg(output, destination, 50);
            if (destination.length() > MAX_FILE_BYTES)
                throw new IOException("图片仍然过大，请选择尺寸较小的图片。");
            return destination.getAbsolutePath();
        } catch (Exception error) {
            if (destination.exists()) destination.delete();
            throw error;
        } finally {
            output.recycle();
        }
    }

    private static void writeJpeg(Bitmap bitmap, File destination, int quality) throws IOException {
        try (FileOutputStream out = new FileOutputStream(destination, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
                throw new IOException("图片压缩失败。");
        }
    }

    static Bitmap thumbnail(String path, int maxPixels) {
        if (path == null || path.isEmpty()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxPixels &&
                sample < 1024) sample *= 2;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(path, options);
        } catch (OutOfMemoryError error) {
            return null;
        }
    }

    static void delete(String path) {
        if (path == null || path.isEmpty() || imageRoot == null) return;
        try {
            File file = new File(path).getCanonicalFile();
            File folder = imageRoot.getCanonicalFile();
            if (file.getParentFile().equals(folder) && file.getName().endsWith(".jpg"))
                file.delete();
        } catch (IOException ignored) { }
    }
}
