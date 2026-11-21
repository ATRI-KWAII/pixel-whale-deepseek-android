package com.pixelwhale.deepseek;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local conversation database. Every message and summary belongs to one chat ID. */
final class ConversationStore extends SQLiteOpenHelper {
    static final String DEFAULT_SYSTEM_PROMPT =
            "你是一位友善、可靠的中文助手。先直接回答用户的问题，再按需要补充步骤或例子。" +
            "表达清晰简洁；不确定时说明不确定性，不编造事实。" +
            "如果用户发送图片，请结合图片内容与用户的问题作答。遵循用户在当前会话中的具体要求。";

    static final class Conversation {
        final long id;
        final String title;
        final String model;
        final String systemPrompt;
        final String summary;
        final long summaryUntilId;
        final long updatedAt;
        final String preview;
        final boolean webEnabled;
        final boolean pinned;

        Conversation(long id, String title, String model, String systemPrompt, String summary,
                     long summaryUntilId, long updatedAt, String preview, boolean webEnabled, boolean pinned) {
            this.id = id;
            this.title = title;
            this.model = model;
            this.systemPrompt = systemPrompt;
            this.summary = summary;
            this.summaryUntilId = summaryUntilId;
            this.updatedAt = updatedAt;
            this.preview = preview;
            this.webEnabled = webEnabled;
            this.pinned = pinned;
        }
    }

    static final class Message {
        final long id;
        final String role;
        final String content;
        final String imagePath;
        final String filePath;
        String fileId;
        final String serverToolCalls;
        final String reasoningContent;
        final String sources;
        String extraAttachments;

        Message(long id, String role, String content) {
            this(id, role, content, null);
        }

        Message(long id, String role, String content, String imagePath) {
            this(id, role, content, imagePath, null, null, null, null, null, null);
        }

        Message(long id, String role, String content, String imagePath, String filePath, String fileId) {
            this(id, role, content, imagePath, filePath, fileId, null, null, null, null);
        }

        Message(long id, String role, String content, String imagePath, String filePath, String fileId,
                String serverToolCalls, String sources, String extraAttachments, String reasoningContent) {
            this.id = id;
            this.role = role;
            this.content = content;
            this.imagePath = imagePath;
            this.filePath = filePath;
            this.fileId = fileId;
            this.serverToolCalls = serverToolCalls;
            this.reasoningContent = reasoningContent;
            this.sources = sources;
            this.extraAttachments = extraAttachments;
        }
    }

    private final Context app;
    private final SharedPreferences ui;

    ConversationStore(Context context) {
        super(context.getApplicationContext(), "pixel_whale.db", null, 9);
        app = context.getApplicationContext();
        ImageStore.init(app);
        FileStore.init(app);
        ui = app.getSharedPreferences("ui_state", Context.MODE_PRIVATE);
        migrateLegacy();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, model TEXT NOT NULL, system_prompt TEXT NOT NULL DEFAULT '', " +
                "summary TEXT NOT NULL DEFAULT '', " +
                "summary_until_id INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, " +
                "web_enabled INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "chat_id INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, " +
                "image_path TEXT, file_path TEXT, file_id TEXT, server_tool_calls TEXT, sources TEXT, " +
                "extra_attachments TEXT, reasoning_content TEXT, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX messages_chat_id ON messages(chat_id, id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("UPDATE chats SET model='think' WHERE model='deepseek-reasoner'");
            db.execSQL("UPDATE chats SET model='flash' WHERE model='deepseek-chat'");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN image_path TEXT");
            db.execSQL("UPDATE chats SET system_prompt=? WHERE system_prompt=''",
                    new Object[]{DEFAULT_SYSTEM_PROMPT});
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN file_path TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN file_id TEXT");
        }
        if (oldVersion < 5) db.execSQL("ALTER TABLE messages ADD COLUMN server_tool_calls TEXT");
        if (oldVersion < 6) db.execSQL("ALTER TABLE messages ADD COLUMN sources TEXT");
        if (oldVersion < 7) db.execSQL("ALTER TABLE chats ADD COLUMN web_enabled INTEGER NOT NULL DEFAULT 0");
        if (oldVersion < 8) db.execSQL("ALTER TABLE messages ADD COLUMN extra_attachments TEXT");
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reasoning_content TEXT");
            db.execSQL("ALTER TABLE chats ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
        }
    }

    private void migrateLegacy() {
        if (ui.getBoolean("legacy_migrated", false)) return;
        List<ChatStore.Message> legacy = new ChatStore(app).load();
        if (!legacy.isEmpty()) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            long chatId;
            try {
                chatId = insertChat(db, "之前的聊天");
                for (ChatStore.Message item : legacy) insertMessage(db, chatId, item.role, item.content);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            ui.edit().putLong("selected_chat", chatId).apply();
        }
        ui.edit().putBoolean("legacy_migrated", true).apply();
    }

    long createChat() {
        long id = insertChat(getWritableDatabase(), "新对话");
        selectChat(id);
        return id;
    }

    private long insertChat(SQLiteDatabase db, String title) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("model", "flash");
        values.put("system_prompt", DEFAULT_SYSTEM_PROMPT);
        values.put("updated_at", System.currentTimeMillis());
        return db.insertOrThrow("chats", null, values);
    }

    void selectChat(long id) {
        ui.edit().putLong("selected_chat", id).apply();
    }

    Conversation selectedChat() {
        Conversation selected = getChat(ui.getLong("selected_chat", -1));
        if (selected != null) return selected;
        List<Conversation> chats = search("");
        if (!chats.isEmpty()) {
            selectChat(chats.get(0).id);
            return chats.get(0);
        }
        return getChat(createChat());
    }

    Conversation getChat(long id) {
        try (Cursor cursor = getReadableDatabase().rawQuery(chatSelect() + " WHERE c.id=?",
                new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? readChat(cursor) : null;
        }
    }

    List<Conversation> search(String query) {
        List<Conversation> result = new ArrayList<>();
        String trimmed = query.trim();
        String sql = chatSelect();
        String[] args = null;
        if (!trimmed.isEmpty()) {
            sql += " WHERE c.title LIKE ? OR EXISTS (SELECT 1 FROM messages m " +
                    "WHERE m.chat_id=c.id AND m.content LIKE ?)";
            String needle = "%" + trimmed + "%";
            args = new String[]{needle, needle};
        }
        sql += " ORDER BY c.pinned DESC, c.updated_at DESC, c.id DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) result.add(readChat(cursor));
        }
        return result;
    }

    private String chatSelect() {
        return "SELECT c.id,c.title,c.model,c.system_prompt,c.summary,c.summary_until_id,c.updated_at," +
                "COALESCE((SELECT content FROM messages WHERE chat_id=c.id ORDER BY id DESC LIMIT 1),'')," +
                "c.web_enabled,c.pinned " +
                "FROM chats c";
    }

    private Conversation readChat(Cursor cursor) {
        return new Conversation(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4), cursor.getLong(5),
                cursor.getLong(6), cursor.getString(7), cursor.getInt(8) != 0, cursor.getInt(9) != 0);
    }

    List<Message> messages(long chatId) {
        List<Message> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,role,content,image_path,file_path,file_id,server_tool_calls,sources,extra_attachments,reasoning_content FROM messages WHERE chat_id=? ORDER BY id",
                new String[]{String.valueOf(chatId)})) {
            while (cursor.moveToNext()) {
                result.add(new Message(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8), cursor.getString(9)));
            }
        }
        return result;
    }

    long addMessage(long chatId, String role, String content) {
        return addMessage(chatId, role, content, null);
    }

    void saveReply(long chatId, long replacedId, DeepSeekClient.Reply reply) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            ContentValues message = new ContentValues();
            message.put("chat_id", chatId);
            message.put("role", "assistant");
            message.put("content", reply.text);
            message.put("server_tool_calls", reply.serverToolCalls);
            message.put("sources", reply.sources);
            message.put("reasoning_content", reply.reasoningContent);
            message.put("created_at", now);
            if (replacedId == 0) db.insertOrThrow("messages", null, message);
            else db.update("messages", message, "id=? AND chat_id=?",
                    new String[]{String.valueOf(replacedId), String.valueOf(chatId)});
            ContentValues chat = new ContentValues();
            chat.put("updated_at", now);
            db.update("chats", chat, "id=?", new String[]{String.valueOf(chatId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    long addMessage(long chatId, String role, String content, String imagePath) {
        return addMessage(chatId, role, content, imagePath, null);
    }

    long addMessage(long chatId, String role, String content, String imagePath, String filePath) {
        return addMessage(chatId, role, content, imagePath, filePath, null);
    }

    long addMessage(long chatId, String role, String content, String imagePath, String filePath,
                    String extraAttachments) {
        SQLiteDatabase db = getWritableDatabase();
        long id = insertMessage(db, chatId, role, content, imagePath, filePath, extraAttachments);
        ContentValues chat = new ContentValues();
        chat.put("updated_at", System.currentTimeMillis());
        if ("user".equals(role)) {
            Conversation conversation = getChat(chatId);
            if (conversation != null && "新对话".equals(conversation.title)) {
                String firstLine = content.replace('\r', '\n').split("\n", 2)[0].trim();
                if (firstLine.length() > 24) firstLine = firstLine.substring(0, 24) + "…";
                chat.put("title", firstLine.isEmpty() ? "新对话" : firstLine);
            }
        }
        db.update("chats", chat, "id=?", new String[]{String.valueOf(chatId)});
        return id;
    }

    private long insertMessage(SQLiteDatabase db, long chatId, String role, String content) {
        return insertMessage(db, chatId, role, content, null);
    }

    private long insertMessage(SQLiteDatabase db, long chatId, String role, String content,
                               String imagePath) {
        return insertMessage(db, chatId, role, content, imagePath, null);
    }

    private long insertMessage(SQLiteDatabase db, long chatId, String role, String content,
                               String imagePath, String filePath) {
        return insertMessage(db, chatId, role, content, imagePath, filePath, null);
    }

    private long insertMessage(SQLiteDatabase db, long chatId, String role, String content,
                               String imagePath, String filePath, String extraAttachments) {
        ContentValues values = new ContentValues();
        values.put("chat_id", chatId);
        values.put("role", role);
        values.put("content", content);
        values.put("image_path", imagePath);
        values.put("file_path", filePath);
        values.put("extra_attachments", extraAttachments);
        values.put("created_at", System.currentTimeMillis());
        return db.insertOrThrow("messages", null, values);
    }

    void setFileId(long messageId, String fileId) {
        ContentValues values = new ContentValues();
        values.put("file_id", fileId);
        getWritableDatabase().update("messages", values, "id=?", new String[]{String.valueOf(messageId)});
    }

    void setExtraAttachments(long messageId, String data) {
        ContentValues values = new ContentValues();
        values.put("extra_attachments", data);
        getWritableDatabase().update("messages", values, "id=?", new String[]{String.valueOf(messageId)});
    }

    void forgetRemoteFile(String id) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        Map<Long, String> changedRows = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT id,extra_attachments FROM messages WHERE extra_attachments IS NOT NULL", null)) {
            while (cursor.moveToNext()) {
                JSONArray extras = new JSONArray(cursor.getString(1));
                boolean changed = false;
                for (int i = 0; i < extras.length(); i++) {
                    JSONObject item = extras.optJSONObject(i);
                    if (item != null && id.equals(item.optString("file_id"))) {
                        item.remove("file_id");
                        changed = true;
                    }
                }
                if (changed) changedRows.put(cursor.getLong(0), extras.toString());
            }
        }
        db.beginTransaction();
        try {
            ContentValues clear = new ContentValues();
            clear.putNull("file_id");
            db.update("messages", clear, "file_id=?", new String[]{id});
            for (Map.Entry<Long, String> row : changedRows.entrySet())
                setExtraAttachments(row.getKey(), row.getValue());
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    static JSONArray extras(Message message) {
        if (message.extraAttachments == null) return new JSONArray();
        try { return new JSONArray(message.extraAttachments); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    static void deleteAttachments(Message message) {
        ImageStore.delete(message.imagePath);
        FileStore.delete(message.filePath);
        JSONArray extras = extras(message);
        for (int i = 0; i < extras.length(); i++) {
            JSONObject item = extras.optJSONObject(i);
            if (item == null) continue;
            if ("image".equals(item.optString("type"))) ImageStore.delete(item.optString("path"));
            else FileStore.delete(item.optString("path"));
        }
    }

    void setPinned(long chatId, boolean pinned) {
        ContentValues values = new ContentValues();
        values.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update("chats", values, "id=?", new String[]{String.valueOf(chatId)});
    }

    String draft(long chatId) {
        return ui.getString("draft_" + chatId, "");
    }

    void saveDraft(long chatId, String draft) {
        SharedPreferences.Editor editor = ui.edit();
        if (draft.isEmpty()) editor.remove("draft_" + chatId);
        else editor.putString("draft_" + chatId, draft);
        editor.apply();
    }

    long branch(long chatId, long throughId, String editedText) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        List<File> copied = new ArrayList<>();
        try {
            db.beginTransaction();
            try {
                Conversation source = getChat(chatId);
                if (source == null) throw new IllegalArgumentException("会话不存在");
                ContentValues chat = new ContentValues();
                chat.put("title", "分支 · " + source.title);
                chat.put("model", source.model);
                chat.put("system_prompt", source.systemPrompt);
                chat.put("web_enabled", source.webEnabled ? 1 : 0);
                chat.put("updated_at", System.currentTimeMillis());
                long branchId = db.insertOrThrow("chats", null, chat);
                long newBoundary = 0;
                boolean found = false;
                for (Message message : messages(chatId)) {
                    boolean last = message.id == throughId;
                    ContentValues row = new ContentValues();
                    row.put("chat_id", branchId);
                    row.put("role", message.role);
                    row.put("content", last && editedText != null ? editedText : message.content);
                    row.put("image_path", copyAttachment(message.imagePath, true, copied));
                    row.put("file_path", copyAttachment(message.filePath, false, copied));
                    row.put("file_id", message.fileId);
                    row.put("server_tool_calls", message.serverToolCalls);
                    row.put("reasoning_content", message.reasoningContent);
                    row.put("sources", message.sources);
                    JSONArray extras = extras(message);
                    for (int i = 0; i < extras.length(); i++) {
                        JSONObject item = extras.getJSONObject(i);
                        item.put("path", copyAttachment(item.getString("path"),
                                "image".equals(item.optString("type")), copied));
                    }
                    if (extras.length() > 0) row.put("extra_attachments", extras.toString());
                    row.put("created_at", System.currentTimeMillis());
                    long newId = db.insertOrThrow("messages", null, row);
                    if (message.id == source.summaryUntilId && !(last && editedText != null))
                        newBoundary = newId;
                    if (last) {
                        found = true;
                        break;
                    }
                }
                if (!found) throw new IllegalArgumentException("消息不存在");
                if (newBoundary > 0) updateSummary(branchId, source.summary, newBoundary);
                db.setTransactionSuccessful();
                return branchId;
            } finally {
                db.endTransaction();
            }
        } catch (Exception failure) {
            for (File file : copied) file.delete();
            throw failure;
        }
    }

    private String copyAttachment(String path, boolean image, List<File> copied) throws Exception {
        if (path == null || path.isEmpty()) return null;
        File directory = new File(app.getFilesDir(), image ? "images" : "documents");
        File source = new File(path).getCanonicalFile();
        if (!directory.getCanonicalFile().equals(source.getParentFile()) || !source.isFile())
            throw new java.io.IOException("附件不存在");
        File target = new File(directory, UUID.randomUUID() + (image ? ".jpg" : "__" + FileStore.name(path)));
        copied.add(target);
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
        }
        return target.getAbsolutePath();
    }

    void updateSummary(long chatId, String summary, long untilId) {
        ContentValues values = new ContentValues();
        values.put("summary", summary);
        values.put("summary_until_id", untilId);
        getWritableDatabase().update("chats", values, "id=?", new String[]{String.valueOf(chatId)});
    }

    void setModel(long chatId, String model) {
        ContentValues values = new ContentValues();
        values.put("model", model);
        getWritableDatabase().update("chats", values, "id=?", new String[]{String.valueOf(chatId)});
    }

    void setWebEnabled(long chatId, boolean enabled) {
        ContentValues values = new ContentValues();
        values.put("web_enabled", enabled ? 1 : 0);
        getWritableDatabase().update("chats", values, "id=?", new String[]{String.valueOf(chatId)});
    }

    void enableWebForAllChats() {
        ContentValues values = new ContentValues();
        values.put("web_enabled", 1);
        getWritableDatabase().update("chats", values, null, null);
    }

    void setSystemPrompt(long chatId, String prompt) {
        ContentValues values = new ContentValues();
        values.put("system_prompt", prompt == null || prompt.trim().isEmpty()
                ? DEFAULT_SYSTEM_PROMPT : prompt.trim());
        getWritableDatabase().update("chats", values, "id=?", new String[]{String.valueOf(chatId)});
    }

    void rename(long chatId, String title) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        getWritableDatabase().update("chats", values, "id=?", new String[]{String.valueOf(chatId)});
    }

    void clear(long chatId) {
        List<Message> old = messages(chatId);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("messages", "chat_id=?", new String[]{String.valueOf(chatId)});
            ContentValues values = new ContentValues();
            values.put("summary", "");
            values.put("summary_until_id", 0);
            values.put("updated_at", System.currentTimeMillis());
            db.update("chats", values, "id=?", new String[]{String.valueOf(chatId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        for (Message message : old) deleteAttachments(message);
        saveDraft(chatId, "");
    }

    void delete(long chatId) {
        List<Message> old = messages(chatId);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("messages", "chat_id=?", new String[]{String.valueOf(chatId)});
            db.delete("chats", "id=?", new String[]{String.valueOf(chatId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        for (Message message : old) deleteAttachments(message);
        saveDraft(chatId, "");
    }
}
