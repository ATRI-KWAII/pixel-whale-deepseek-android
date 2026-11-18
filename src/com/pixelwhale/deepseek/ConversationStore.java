package com.pixelwhale.deepseek;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

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

        Conversation(long id, String title, String model, String systemPrompt, String summary,
                     long summaryUntilId, long updatedAt, String preview) {
            this.id = id;
            this.title = title;
            this.model = model;
            this.systemPrompt = systemPrompt;
            this.summary = summary;
            this.summaryUntilId = summaryUntilId;
            this.updatedAt = updatedAt;
            this.preview = preview;
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
        final String sources;

        Message(long id, String role, String content) {
            this(id, role, content, null);
        }

        Message(long id, String role, String content, String imagePath) {
            this(id, role, content, imagePath, null, null, null, null);
        }

        Message(long id, String role, String content, String imagePath, String filePath, String fileId) {
            this(id, role, content, imagePath, filePath, fileId, null, null);
        }

        Message(long id, String role, String content, String imagePath, String filePath, String fileId,
                String serverToolCalls, String sources) {
            this.id = id;
            this.role = role;
            this.content = content;
            this.imagePath = imagePath;
            this.filePath = filePath;
            this.fileId = fileId;
            this.serverToolCalls = serverToolCalls;
            this.sources = sources;
        }
    }

    private final Context app;
    private final SharedPreferences ui;

    ConversationStore(Context context) {
        super(context.getApplicationContext(), "pixel_whale.db", null, 6);
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
                "summary_until_id INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "chat_id INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, " +
                "image_path TEXT, file_path TEXT, file_id TEXT, server_tool_calls TEXT, sources TEXT, created_at INTEGER NOT NULL)");
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
        sql += " ORDER BY c.updated_at DESC, c.id DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) result.add(readChat(cursor));
        }
        return result;
    }

    private String chatSelect() {
        return "SELECT c.id,c.title,c.model,c.system_prompt,c.summary,c.summary_until_id,c.updated_at," +
                "COALESCE((SELECT content FROM messages WHERE chat_id=c.id ORDER BY id DESC LIMIT 1),'') " +
                "FROM chats c";
    }

    private Conversation readChat(Cursor cursor) {
        return new Conversation(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4), cursor.getLong(5),
                cursor.getLong(6), cursor.getString(7));
    }

    List<Message> messages(long chatId) {
        List<Message> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,role,content,image_path,file_path,file_id,server_tool_calls,sources FROM messages WHERE chat_id=? ORDER BY id",
                new String[]{String.valueOf(chatId)})) {
            while (cursor.moveToNext()) {
                result.add(new Message(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7)));
            }
        }
        return result;
    }

    long addMessage(long chatId, String role, String content) {
        return addMessage(chatId, role, content, null);
    }

    long addAssistantWithTools(long chatId, String content, String toolCalls, String sources) {
        long id = addMessage(chatId, "assistant", content);
        if (toolCalls != null) {
            ContentValues values = new ContentValues();
            values.put("server_tool_calls", toolCalls);
            getWritableDatabase().update("messages", values, "id=?", new String[]{String.valueOf(id)});
        }
        if (sources != null) {
            ContentValues values = new ContentValues();
            values.put("sources", sources);
            getWritableDatabase().update("messages", values, "id=?", new String[]{String.valueOf(id)});
        }
        return id;
    }

    long addMessage(long chatId, String role, String content, String imagePath) {
        return addMessage(chatId, role, content, imagePath, null);
    }

    long addMessage(long chatId, String role, String content, String imagePath, String filePath) {
        SQLiteDatabase db = getWritableDatabase();
        long id = insertMessage(db, chatId, role, content, imagePath, filePath);
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
        ContentValues values = new ContentValues();
        values.put("chat_id", chatId);
        values.put("role", role);
        values.put("content", content);
        values.put("image_path", imagePath);
        values.put("file_path", filePath);
        values.put("created_at", System.currentTimeMillis());
        return db.insertOrThrow("messages", null, values);
    }

    void setFileId(long messageId, String fileId) {
        ContentValues values = new ContentValues();
        values.put("file_id", fileId);
        getWritableDatabase().update("messages", values, "id=?", new String[]{String.valueOf(messageId)});
    }

    boolean removeLastAssistant(long chatId) {
        List<Message> list = messages(chatId);
        if (list.isEmpty()) return false;
        Message last = list.get(list.size() - 1);
        if (!"assistant".equals(last.role)) return false;
        return getWritableDatabase().delete("messages", "id=? AND chat_id=?",
                new String[]{String.valueOf(last.id), String.valueOf(chatId)}) == 1;
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
        for (Message message : old) { ImageStore.delete(message.imagePath); FileStore.delete(message.filePath); }
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
        for (Message message : old) { ImageStore.delete(message.imagePath); FileStore.delete(message.filePath); }
    }
}
