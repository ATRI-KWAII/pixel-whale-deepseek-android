package com.pixelwhale.deepseek;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable conversation archive. API keys and remote file IDs are deliberately excluded. */
final class ConversationBackup {
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 64L * 1024 * 1024;

    private static final class Asset {
        final File source;
        final String entry;
        Asset(String path) throws Exception {
            source = new File(path);
            if (!source.isFile()) throw new Exception("备份失败：附件已不存在：" + source.getName());
            entry = "assets/" + UUID.randomUUID();
        }
    }

    static void exportArchive(ConversationStore store, OutputStream destination) throws Exception {
        JSONArray chats = new JSONArray();
        List<Asset> assets = new ArrayList<>();
        for (ConversationStore.Conversation chat : store.search("")) {
            JSONObject savedChat = new JSONObject();
            savedChat.put("title", chat.title);
            savedChat.put("model", chat.model);
            savedChat.put("system_prompt", chat.systemPrompt);
            savedChat.put("summary", chat.summary);
            savedChat.put("summary_until_id", chat.summaryUntilId);
            savedChat.put("updated_at", chat.updatedAt);
            savedChat.put("web_enabled", chat.webEnabled);
            savedChat.put("pinned", chat.pinned);
            JSONArray messages = new JSONArray();
            for (ConversationStore.Message message : store.messages(chat.id)) {
                JSONObject saved = new JSONObject();
                saved.put("old_id", message.id);
                saved.put("role", message.role);
                saved.put("content", message.content);
                if (message.sources != null) saved.put("sources", message.sources);
                if (message.imagePath != null)
                    saved.put("image_asset", asset(assets, message.imagePath));
                if (message.filePath != null) {
                    saved.put("file_asset", asset(assets, message.filePath));
                    saved.put("file_name", FileStore.name(message.filePath));
                }
                JSONArray extras = ConversationStore.extras(message);
                JSONArray savedExtras = new JSONArray();
                for (int i = 0; i < extras.length(); i++) {
                    JSONObject extra = extras.getJSONObject(i);
                    JSONObject savedExtra = new JSONObject();
                    String type = extra.optString("type");
                    savedExtra.put("type", type);
                    savedExtra.put("asset", asset(assets, extra.getString("path")));
                    if ("file".equals(type)) savedExtra.put("name", FileStore.name(extra.getString("path")));
                    savedExtras.put(savedExtra);
                }
                saved.put("extras", savedExtras);
                messages.put(saved);
            }
            savedChat.put("messages", messages);
            chats.put(savedChat);
        }
        JSONObject manifest = new JSONObject().put("format", "pixel-whale-backup")
                .put("version", 1).put("chats", chats);
        byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        if (manifestBytes.length > MAX_MANIFEST_BYTES) throw new Exception("聊天记录过大，无法导出。" );
        ZipOutputStream zip = new ZipOutputStream(destination);
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(manifestBytes);
        zip.closeEntry();
        for (Asset asset : assets) {
            if (asset.source.length() > MAX_ENTRY_BYTES) throw new Exception("附件超过备份限制。" );
            zip.putNextEntry(new ZipEntry(asset.entry));
            try (InputStream input = new FileInputStream(asset.source)) { copy(input, zip, MAX_ENTRY_BYTES); }
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
    }

    private static String asset(List<Asset> assets, String path) throws Exception {
        Asset asset = new Asset(path);
        assets.add(asset);
        return asset.entry;
    }

    static long importArchive(Context context, ConversationStore store, InputStream source) throws Exception {
        File stage = new File(context.getCacheDir(), "import-" + UUID.randomUUID());
        if (!stage.mkdirs()) throw new Exception("无法创建导入缓存。" );
        List<File> importedFiles = new ArrayList<>();
        try {
            byte[] manifestBytes = null;
            long total = 0;
            int entries = 0;
            try (ZipInputStream zip = new ZipInputStream(source)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > 5000) throw new Exception("备份中的文件过多。" );
                    String name = entry.getName();
                    if ("manifest.json".equals(name)) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        total += copy(zip, out, MAX_MANIFEST_BYTES);
                        manifestBytes = out.toByteArray();
                    } else if (name.matches("assets/[a-fA-F0-9-]{36}")) {
                        File target = new File(stage, name.substring(7));
                        try (OutputStream out = new FileOutputStream(target)) {
                            total += copy(zip, out, MAX_ENTRY_BYTES);
                        }
                    } else throw new Exception("备份文件格式不正确。" );
                    if (total > MAX_ARCHIVE_BYTES) throw new Exception("备份超过 512 MB。" );
                    zip.closeEntry();
                }
            }
            if (manifestBytes == null) throw new Exception("备份缺少聊天清单。" );
            JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!"pixel-whale-backup".equals(manifest.optString("format")) ||
                    manifest.optInt("version") != 1) throw new Exception("不支持此备份格式。" );
            JSONArray chats = manifest.getJSONArray("chats");
            if (chats.length() > 1000) throw new Exception("备份中的会话过多。" );
            SQLiteDatabase db = store.getWritableDatabase();
            long firstChat = -1;
            db.beginTransaction();
            try {
                for (int i = 0; i < chats.length(); i++) {
                    JSONObject chat = chats.getJSONObject(i);
                    ContentValues values = new ContentValues();
                    values.put("title", chat.optString("title", "导入的会话"));
                    String model = chat.optString("model", "flash");
                    values.put("model", "pro".equals(model) || "think".equals(model) ? model : "flash");
                    values.put("system_prompt", chat.optString("system_prompt", ConversationStore.DEFAULT_SYSTEM_PROMPT));
                    values.put("summary", chat.optString("summary", ""));
                    values.put("summary_until_id", 0);
                    values.put("updated_at", chat.optLong("updated_at", System.currentTimeMillis()));
                    values.put("web_enabled", chat.optBoolean("web_enabled", false) ? 1 : 0);
                    values.put("pinned", chat.optBoolean("pinned", false) ? 1 : 0);
                    long chatId = db.insertOrThrow("chats", null, values);
                    if (firstChat < 0) firstChat = chatId;
                    JSONArray messages = chat.getJSONArray("messages");
                    if (messages.length() > 100000) throw new Exception("备份中的消息过多。" );
                    Map<Long, Long> oldToNew = new HashMap<>();
                    for (int j = 0; j < messages.length(); j++) {
                        JSONObject message = messages.getJSONObject(j);
                        String role = message.optString("role", "");
                        if (!"user".equals(role) && !"assistant".equals(role))
                            throw new Exception("备份含有无效消息。" );
                        ContentValues row = new ContentValues();
                        row.put("chat_id", chatId);
                        row.put("role", role);
                        row.put("content", message.optString("content", ""));
                        row.put("created_at", System.currentTimeMillis() + j);
                        row.put("sources", message.optString("sources", ""));
                        if (message.has("image_asset"))
                            row.put("image_path", restore(context, stage, message.getString("image_asset"),
                                    "image", null, importedFiles));
                        if (message.has("file_asset"))
                            row.put("file_path", restore(context, stage, message.getString("file_asset"),
                                    "file", message.optString("file_name", "document"), importedFiles));
                        JSONArray savedExtras = message.optJSONArray("extras");
                        JSONArray extras = new JSONArray();
                        if (savedExtras != null) for (int k = 0; k < savedExtras.length(); k++) {
                            JSONObject extra = savedExtras.getJSONObject(k);
                            String type = extra.getString("type");
                            if (!"image".equals(type) && !"file".equals(type))
                                throw new Exception("备份含有无效附件。" );
                            String path = restore(context, stage, extra.getString("asset"), type,
                                    extra.optString("name", "document"), importedFiles);
                            extras.put(new JSONObject().put("type", type).put("path", path));
                        }
                        if (extras.length() > 0) row.put("extra_attachments", extras.toString());
                        long messageId = db.insertOrThrow("messages", null, row);
                        oldToNew.put(message.optLong("old_id", -1), messageId);
                    }
                    long oldUntil = chat.optLong("summary_until_id", 0);
                    if (oldUntil > 0 && oldToNew.containsKey(oldUntil)) {
                        ContentValues summary = new ContentValues();
                        summary.put("summary_until_id", oldToNew.get(oldUntil));
                        db.update("chats", summary, "id=?", new String[]{String.valueOf(chatId)});
                    }
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            return firstChat;
        } catch (Exception error) {
            for (File file : importedFiles) file.delete();
            throw error;
        } finally {
            File[] staged = stage.listFiles();
            if (staged != null) for (File file : staged) file.delete();
            stage.delete();
        }
    }

    private static String restore(Context context, File stage, String entry, String type,
                                  String name, List<File> importedFiles) throws Exception {
        if (!entry.matches("assets/[a-fA-F0-9-]{36}")) throw new Exception("附件路径无效。" );
        File source = new File(stage, entry.substring(7));
        if (!source.isFile()) throw new Exception("备份缺少附件。" );
        File directory = new File(context.getFilesDir(), "image".equals(type) ? "images" : "documents");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("无法保存导入的附件。" );
        String safeName = name == null ? "document" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeName.length() > 80) safeName = safeName.substring(safeName.length() - 80);
        File target = new File(directory, UUID.randomUUID() + ("image".equals(type) ? ".jpg" : "__" + safeName));
        importedFiles.add(target);
        try (InputStream input = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            copy(input, out, MAX_ENTRY_BYTES);
        }
        return target.getAbsolutePath();
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws Exception {
        byte[] buffer = new byte[8192];
        long size = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            size += count;
            if (size > limit) throw new Exception("备份中的单个文件过大。" );
            out.write(buffer, 0, count);
        }
        return size;
    }
}
