package com.pixelwhale.deepseek;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.util.LruCache;

import java.util.List;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.InputStream;
import java.io.OutputStream;

public final class MainActivity extends Activity {
    private static final int INK = 0xff312b26;
    private static final int ACCENT = 0xff8d432e;
    private static final int PAPER = 0xfffaf7f0;
    private static final int MUTED = 0xff6f645a;
    private static final int SURFACE = 0xfffffdfa;
    private static final int BORDER = 0xffe1d7ca;
    private static final int TERRACOTTA = 0xffa94e35;
    private static final int SAND = 0xfff0e9de;
    private static final int IMAGE_REQUEST = 18;
    private static final int FILE_REQUEST = 19;
    private static final int EXPORT_REQUEST = 20;
    private static final int IMPORT_REQUEST = 21;
    private static final int PAGE_SIZE = 40;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailWorker = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private SecretStore secrets;
    private MemoryStore memories;
    private boolean keyConfigured;
    private ConversationStore store;
    private long currentId;
    private long busyChatId = -1;
    private long regeneratingId;
    private long errorChatId = -1;
    private boolean busy;
    private Runnable retryAction;
    private String partialText;
    private String progressText;
    private String errorText;
    private TextView streamingBubble;
    private DeepSeekClient.Control activeControl;
    private TextView titleView;
    private TextView modelView;
    private TextView sendButton;
    private TextView webButton;
    private boolean webEnabled;
    private EditText composer;
    private LinearLayout chatColumn;
    private ScrollView chatScroll;
    private LinearLayout pendingImageRow;
    private ImageView pendingImagePreview;
    private TextView pendingImageLabel;
    private String pendingImagePath;
    private String pendingFilePath;
    private final List<String> extraImages = new ArrayList<>();
    private final List<String> extraFiles = new ArrayList<>();
    private TextView pendingFileLabel;
    private boolean imageLoading;
    private boolean fileLoading;
    private boolean archiveBusy;
    private int imageSelectionId;
    private int fileSelectionId;
    private int visibleMessageCount = PAGE_SIZE;
    private long renderedChatId = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().setStatusBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        secrets = new SecretStore(this);
        memories = new MemoryStore(this);
        NightMemoryJob.sync(this);
        try { keyConfigured = secrets.read() != null; }
        catch (Exception ignored) { }
        store = new ConversationStore(this);
        currentId = store.selectedChat().id;
        android.content.SharedPreferences legacySettings = getPreferences(MODE_PRIVATE);
        if (!legacySettings.getBoolean("web_setting_migrated", false)) {
            if (legacySettings.getBoolean("web_enabled", false)) store.enableWebForAllChats();
            legacySettings.edit().putBoolean("web_setting_migrated", true).apply();
        }
        webEnabled = store.getChat(currentId).webEnabled;
        buildScreen();
        restoreDraft();
        renderChat(true);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        root.requestFocus();

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(13), 0, dp(13), 0);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView menu = headerButton("☰", 19);
        menu.setContentDescription("会话历史");
        header.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        menu.setOnClickListener(v -> showConversationList());

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(8), 0, dp(8), 0);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        titleView = label("像素鲸语", 17, INK, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(titleView);
        modelView = label("", 12, ACCENT, true);
        modelView.setSingleLine(true);
        modelView.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(modelView);
        heading.setContentDescription("选择模型");
        heading.setBackground(pressable(box(Color.TRANSPARENT, 10, 0, 0)));
        heading.setOnClickListener(v -> showModelPicker());

        TextView add = headerButton("＋", 23);
        add.setContentDescription("新对话");
        header.addView(add, new LinearLayout.LayoutParams(dp(44), dp(44)));
        add.setOnClickListener(v -> newChat());
        TextView more = headerButton("⋯", 24);
        more.setContentDescription("会话设置");
        header.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        more.setOnClickListener(v -> showChatMenu());

        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatScroll.setClipToPadding(false);
        chatScroll.setVerticalScrollBarEnabled(false);
        root.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        chatColumn = new LinearLayout(this);
        chatColumn.setOrientation(LinearLayout.VERTICAL);
        chatColumn.setPadding(dp(20), dp(12), dp(20), dp(22));
        chatScroll.addView(chatColumn, new ScrollView.LayoutParams(-1, -2));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(8), dp(16), dp(13));
        bottom.setBackgroundColor(PAPER);
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        pendingImageRow = new LinearLayout(this);
        pendingImageRow.setGravity(Gravity.CENTER_VERTICAL);
        pendingImageRow.setPadding(dp(9), dp(6), dp(9), dp(6));
        pendingImageRow.setBackground(box(SAND, 15, BORDER, 1));
        LinearLayout.LayoutParams pendingParams = new LinearLayout.LayoutParams(-1, dp(68));
        pendingParams.bottomMargin = dp(8);
        bottom.addView(pendingImageRow, pendingParams);
        pendingImagePreview = new ImageView(this);
        pendingImagePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pendingImagePreview.setBackground(box(SAND, 8, 0, 0));
        pendingImagePreview.setClipToOutline(true);
        pendingImageRow.addView(pendingImagePreview,
                new LinearLayout.LayoutParams(dp(55), dp(55)));
        pendingImageLabel = label("图片已准备", 13, INK, true);
        LinearLayout.LayoutParams imageLabelParams = new LinearLayout.LayoutParams(0, -2, 1);
        imageLabelParams.leftMargin = dp(10);
        pendingImageRow.addView(pendingImageLabel, imageLabelParams);
        TextView removeImage = label("移除 ×", 12, Color.rgb(176, 92, 78), true);
        removeImage.setPadding(dp(8), dp(12), dp(6), dp(12));
        pendingImageRow.addView(removeImage);
        removeImage.setOnClickListener(v -> discardPendingImage());
        pendingImageRow.setVisibility(View.GONE);

        pendingFileLabel = label("", 12, INK, true);
        pendingFileLabel.setPadding(dp(12), dp(9), dp(12), dp(9));
        pendingFileLabel.setBackground(pressable(box(SAND, 12, BORDER, 1)));
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(-1, -2);
        fileParams.bottomMargin = dp(7);
        bottom.addView(pendingFileLabel, fileParams);
        pendingFileLabel.setOnClickListener(v -> {
            discardPendingFile();
        });
        updatePendingFile();

        LinearLayout composeCard = new LinearLayout(this);
        composeCard.setOrientation(LinearLayout.VERTICAL);
        composeCard.setPadding(dp(8), dp(4), dp(8), dp(6));
        composeCard.setBackground(box(SURFACE, 20, BORDER, 1));
        composeCard.setElevation(dp(2));
        bottom.addView(composeCard, new LinearLayout.LayoutParams(-1, -2));

        composer = new EditText(this);
        composer.setTextSize(16);
        composer.setTextColor(INK);
        composer.setHintTextColor(MUTED);
        composer.setHint("发消息给小鲸…");
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setMinLines(1);
        composer.setMaxLines(5);
        composer.setPadding(dp(10), dp(11), dp(10), dp(8));
        composer.setBackgroundColor(Color.TRANSPARENT);
        composeCard.addView(composer, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        composeCard.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        TextView attach = headerButton("＋", 22);
        attach.setContentDescription("添加图片或文档");
        toolbar.addView(attach, new LinearLayout.LayoutParams(dp(44), dp(44)));
        attach.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(new String[]{"添加图片", "添加文档"}, (dialog, which) -> {
                    if (which == 0) pickImage(); else pickFile();
                }).show());

        webButton = label("", 13, ACCENT, true);
        webButton.setGravity(Gravity.CENTER);
        webButton.setPadding(dp(12), 0, dp(12), 0);
        webButton.setMinimumHeight(dp(44));
        toolbar.addView(webButton, new LinearLayout.LayoutParams(-2, -2));
        webButton.setOnClickListener(v -> {
            webEnabled = !webEnabled;
            store.setWebEnabled(currentId, webEnabled);
            updateWebButton();
        });
        updateWebButton();
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        sendButton = label("↑", 24, Color.WHITE, true);
        sendButton.setGravity(Gravity.CENTER);
        toolbar.addView(sendButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        sendButton.setOnClickListener(v -> {
            if (busy && busyChatId == currentId) stopRequest();
            else send();
        });
    }

    private void updateWebButton() {
        webButton.setText(webEnabled ? "✓ 联网" : "联网");
        webButton.setContentDescription(webEnabled ? "联网已开启，点击关闭" : "联网已关闭，点击开启");
        webButton.setTextColor(webEnabled ? ACCENT : MUTED);
        webButton.setBackground(pressable(box(webEnabled ? SAND : Color.TRANSPARENT, 12, 0, 0)));
    }

    private void updatePendingFile() {
        pendingFileLabel.setVisibility(pendingFilePath == null && extraFiles.isEmpty() ? View.GONE : View.VISIBLE);
        if (pendingFilePath != null)
            pendingFileLabel.setText("▤  已选 " + (extraFiles.size() + 1) + " 份文档 · 点此移除全部");
    }

    private void renderChat() { renderChat(false); }

    private void renderChat(boolean scrollBottom) {
        int oldScrollY = chatScroll.getScrollY();
        ConversationStore.Conversation chat = store.getChat(currentId);
        if (chat == null) {
            chat = store.selectedChat();
            currentId = chat.id;
        }
        webEnabled = chat.webEnabled;
        updateWebButton();
        if (renderedChatId != currentId) {
            visibleMessageCount = PAGE_SIZE;
            renderedChatId = currentId;
            scrollBottom = true;
        }
        titleView.setText("新对话".equals(chat.title) ? "像素鲸语" : chat.title);
        String modelName = "pro".equals(chat.model) ? "DeepSeek Pro · 思考" :
                ("think".equals(chat.model) ? "DeepSeek Flash · 思考" : "DeepSeek Flash");
        modelView.setText(modelName + "  ⌄");
        chatColumn.removeAllViews();
        streamingBubble = null;
        List<ConversationStore.Message> messages = store.messages(currentId);
        chatColumn.setGravity(messages.isEmpty() ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        if (messages.isEmpty()) addWelcome();
        else {
            if (!chat.summary.isEmpty()) addSummaryBanner(chat);
            int first = Math.max(0, messages.size() - visibleMessageCount);
            if (first > 0) addLoadOlder();
            for (int i = first; i < messages.size(); i++) {
                if (busy && busyChatId == currentId && messages.get(i).id == regeneratingId) continue;
                addMessage(messages.get(i), i == messages.size() - 1);
            }
        }
        if (busy && busyChatId == currentId) {
            if (partialText == null || partialText.isEmpty())
                addStatus(progressText, false, null);
            else addMessage(new ConversationStore.Message(-1, "assistant", partialText), false);
        }
        if (errorText != null && errorChatId == currentId)
            addStatus(errorText, true, retryAction);
        else if (!busy && !messages.isEmpty() && "user".equals(messages.get(messages.size() - 1).role))
            addStatus("这条消息还没有回复。", false, this::requestReply);
        boolean stoppable = busy && busyChatId == currentId;
        sendButton.setText(stoppable ? "■" : "↑");
        sendButton.setTextSize(stoppable ? 16 : 24);
        sendButton.setContentDescription(stoppable ? "停止生成" : "发送");
        sendButton.setBackground(pressable(box(stoppable ? INK : TERRACOTTA, 13, 0, 0)));
        sendButton.setEnabled(!archiveBusy && (!busy || stoppable));
        sendButton.setAlpha(sendButton.isEnabled() ? 1f : .45f);
        if (scrollBottom) scrollToBottom();
        else chatScroll.post(() -> chatScroll.scrollTo(0, oldScrollY));
    }

    private void addLoadOlder() {
        TextView older = label("↑  加载更早消息", 12, ACCENT, true);
        older.setGravity(Gravity.CENTER);
        older.setBackground(pressable(box(SURFACE, 13, BORDER, 1)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(39));
        params.bottomMargin = dp(9);
        chatColumn.addView(older, params);
        older.setOnClickListener(v -> {
            int oldHeight = chatColumn.getHeight();
            int oldY = chatScroll.getScrollY();
            visibleMessageCount += PAGE_SIZE;
            renderChat(false);
            chatScroll.post(() -> chatScroll.scrollTo(0,
                    oldY + Math.max(0, chatColumn.getHeight() - oldHeight)));
        });
    }

    private void addWelcome() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(4), dp(24), dp(4), dp(32));
        chatColumn.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        ImageView emblem = new ImageView(this);
        emblem.setImageResource(R.drawable.whale_mark);
        emblem.setPadding(dp(11), dp(11), dp(11), dp(11));
        emblem.setBackground(box(SAND, 19, BORDER, 1));
        hero.addView(emblem, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView welcome = label("今天想聊些什么？", 28, INK, false);
        welcome.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        welcome.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams welcomeParams = new LinearLayout.LayoutParams(-1, -2);
        welcomeParams.topMargin = dp(18);
        hero.addView(welcome, welcomeParams);

        if (!keyConfigured) {
            TextView setup = label("连接 DeepSeek API  ›", 13, ACCENT, true);
            setup.setGravity(Gravity.CENTER);
            setup.setMinimumHeight(dp(48));
            LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(-1, -2);
            setupParams.topMargin = dp(11);
            hero.addView(setup, setupParams);
            setup.setOnClickListener(v -> showSettings());
        }
    }

    private void addSummaryBanner(ConversationStore.Conversation chat) {
        TextView banner = label("查看较早对话摘要  ›", 13, ACCENT, true);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(12), dp(10), dp(12), dp(10));
        banner.setBackground(pressable(box(SAND, 13, BORDER, 1)));
        chatColumn.addView(banner, new LinearLayout.LayoutParams(-1, -2));
        banner.setOnClickListener(v -> showSummary(chat));
    }

    private void addMessage(ConversationStore.Message message, boolean last) {
        boolean user = "user".equals(message.role);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(user ? Gravity.RIGHT | Gravity.TOP : Gravity.LEFT | Gravity.TOP);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(17);
        chatColumn.addView(row, rowParams);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(user ? -2 : -1, -2);
        bodyParams.leftMargin = user ? dp(38) : 0;
        row.addView(body, bodyParams);
        if (!user) {
            LinearLayout identity = new LinearLayout(this);
            identity.setGravity(Gravity.CENTER_VERTICAL);
            ImageView mark = new ImageView(this);
            mark.setImageResource(R.drawable.whale_mark);
            mark.setPadding(dp(4), dp(4), dp(4), dp(4));
            mark.setBackground(box(SAND, 9, 0, 0));
            identity.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView name = label("小鲸", 13, INK, true);
            name.setPadding(dp(8), 0, 0, 0);
            identity.addView(name);
            LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(-2, -2);
            identityParams.bottomMargin = dp(7);
            body.addView(identity, identityParams);
        }
        if (message.imagePath != null && !message.imagePath.isEmpty()) {
            ImageView picture = new ImageView(this);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setBackground(box(SAND, 12, 0, 0));
            picture.setClipToOutline(true);
            LinearLayout.LayoutParams pictureParams = new LinearLayout.LayoutParams(
                    dp(190), dp(150));
            pictureParams.bottomMargin = dp(6);
            body.addView(picture, pictureParams);
            showThumbnail(picture, message.imagePath, 420);
            picture.setOnClickListener(v -> showFullImage(message.imagePath));
        }
        JSONArray extras = ConversationStore.extras(message);
        for (int i = 0; i < extras.length(); i++) {
            JSONObject extra = extras.optJSONObject(i);
            if (extra == null || !"image".equals(extra.optString("type"))) continue;
            String path = extra.optString("path", "");
            ImageView picture = new ImageView(this);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setClipToOutline(true);
            LinearLayout.LayoutParams pictureParams = new LinearLayout.LayoutParams(dp(190), dp(150));
            pictureParams.bottomMargin = dp(6);
            body.addView(picture, pictureParams);
            showThumbnail(picture, path, 420);
            picture.setOnClickListener(v -> showFullImage(path));
        }
        if (message.filePath != null) {
            TextView file = label("▤  " + FileStore.name(message.filePath), 12, ACCENT, true);
            file.setPadding(dp(10), dp(7), dp(10), dp(7));
            file.setBackground(box(SAND, 10, BORDER, 1));
            body.addView(file, new LinearLayout.LayoutParams(-2, -2));
        }
        for (int i = 0; i < extras.length(); i++) {
            JSONObject extra = extras.optJSONObject(i);
            if (extra == null || !"file".equals(extra.optString("type"))) continue;
            TextView file = label("▤  " + FileStore.name(extra.optString("path", "")), 12, ACCENT, true);
            file.setPadding(dp(10), dp(7), dp(10), dp(7));
            file.setBackground(box(SAND, 10, BORDER, 1));
            body.addView(file, new LinearLayout.LayoutParams(-2, -2));
        }
        if (user || message.id == -1) {
            TextView bubble = messageText(message.content);
            if (user) {
                bubble.setPadding(dp(14), dp(11), dp(14), dp(11));
                bubble.setBackground(box(SAND, 17, 0, 0));
            } else streamingBubble = bubble;
            body.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        } else {
            for (MarkdownText.Block block : MarkdownText.blocks(message.content)) {
                if (block.language != null) addCodeBlock(body, block);
                else if (!block.text.isEmpty()) {
                    TextView paragraph = messageText("");
                    paragraph.setText(MarkdownText.render(block.text));
                    body.addView(paragraph, new LinearLayout.LayoutParams(-1, -2));
                }
            }
        }
        if (!user && message.sources != null && !message.sources.isEmpty()) {
            TextView heading = label("参考来源", 11, MUTED, true);
            heading.setPadding(dp(3), dp(8), 0, dp(3));
            body.addView(heading);
            for (String line : message.sources.split("\n")) {
                int at = line.indexOf("http://");
                if (at < 0) at = line.indexOf("https://");
                if (at < 0) continue;
                String url = line.substring(at).trim();
                String title = line.substring(0, at).replaceFirst("^\\d+\\.\\s*", "").replace("·", "").trim();
                if (title.isEmpty()) title = Uri.parse(url).getHost();
                TextView source = label("↗  " + title, 12, ACCENT, true);
                source.setMaxLines(2);
                source.setEllipsize(TextUtils.TruncateAt.END);
                source.setPadding(dp(10), dp(8), dp(10), dp(8));
                source.setBackground(pressable(box(SAND, 10, BORDER, 1)));
                LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(-1, -2);
                sourceParams.bottomMargin = dp(4);
                body.addView(source, sourceParams);
                source.setOnClickListener(v -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                    catch (Exception e) { Toast.makeText(this, "无法打开来源链接", Toast.LENGTH_SHORT).show(); }
                });
            }
        }
        if (message.id == -1) return;
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(3), 0, 0);
        body.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        TextView copy = actionChip("复制");
        actions.addView(copy);
        copy.setOnClickListener(v -> copyText(message.content));

        if (user) {
            TextView edit = actionChip("编辑");
            actions.addView(edit);
            edit.setOnClickListener(v -> editAsBranch(message));
        } else if (last) {
            TextView retry = actionChip("重新生成");
            actions.addView(retry);
            retry.setOnClickListener(v -> regenerate());
        }
        TextView more = actionChip("⋯");
        more.setContentDescription("更多消息操作");
        actions.addView(more);
        more.setOnClickListener(v -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(this, more);
            menu.getMenu().add(0, 1, 0, "从此处开启新对话");
            menu.getMenu().add(0, 2, 1, "分享这条消息");
            menu.getMenu().add(0, 3, 2, "保存为记忆");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) openBranch(message, null);
                else if (item.getItemId() == 2) shareText(message.content, "分享消息");
                else editMemory(null, message.content);
                return true;
            });
            menu.show();
        });
    }

    private TextView messageText(String text) {
        TextView view = label(text, 16, INK, false);
        view.setTextIsSelectable(true);
        view.setLineSpacing(dp(4), 1.05f);
        return view;
    }

    private void addCodeBlock(LinearLayout body, MarkdownText.Block block) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(SAND, 12, BORDER, 1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(9);
        cardParams.bottomMargin = dp(9);
        body.addView(card, cardParams);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), 0, dp(5), 0);
        card.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        TextView language = label(block.language.isEmpty() ? "代码" : block.language, 12, MUTED, true);
        language.setSingleLine(true);
        language.setEllipsize(TextUtils.TruncateAt.END);
        toolbar.addView(language, new LinearLayout.LayoutParams(0, -2, 1));
        TextView copy = actionChip("复制代码");
        toolbar.addView(copy);
        copy.setOnClickListener(v -> copyText(block.text));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setFillViewport(true);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        TextView code = messageText(block.text);
        code.setTextSize(13);
        code.setTypeface(Typeface.MONOSPACE);
        code.setPadding(dp(12), dp(3), dp(12), dp(12));
        scroll.addView(code, new HorizontalScrollView.LayoutParams(-2, -2));
    }

    private TextView actionChip(String title) {
        TextView action = label(title, 12, MUTED, true);
        action.setGravity(Gravity.CENTER);
        action.setMinimumWidth(dp(44));
        action.setMinimumHeight(dp(40));
        action.setPadding(dp(9), dp(4), dp(9), dp(4));
        action.setBackground(pressable(box(Color.TRANSPARENT, 9, 0, 0)));
        return action;
    }

    private void addStatus(String status, boolean failed, Runnable retry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(11), dp(14), dp(11));
        card.setBackground(box(failed ? Color.rgb(255, 245, 239) : SAND, 16,
                failed ? Color.rgb(237, 189, 169) : BORDER, 1));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        chatColumn.addView(card, params);
        card.addView(label((failed ? "⚠  " : "✦  ") + status, 13,
                failed ? Color.rgb(137, 73, 57) : MUTED, false));
        if (retry != null) {
            TextView button = actionChip("生成回复");
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, -2);
            retryParams.topMargin = dp(8);
            card.addView(button, retryParams);
            button.setOnClickListener(v -> retry.run());
        }
    }

    private TextView headerButton(String text, int size) {
        TextView view = label(text, size, INK, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(pressable(box(Color.TRANSPARENT, 12, 0, 0)));
        return view;
    }

    private void copyText(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("像素鲸语", value));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private void showConversationList() {
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        root.setFocusableInTouchMode(true);
        dialog.setContentView(root);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(9), 0, dp(14), 0);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(65)));
        TextView back = headerButton("‹", 27);
        back.setContentDescription("关闭会话历史");
        top.addView(back, new LinearLayout.LayoutParams(dp(42), dp(44)));
        back.setOnClickListener(v -> dialog.dismiss());
        TextView heading = label("会话历史", 18, INK, true);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView create = label("＋ 新对话", 13, Color.WHITE, true);
        create.setGravity(Gravity.CENTER);
        create.setBackground(pressable(box(TERRACOTTA, 11, 0, 0)));
        top.addView(create, new LinearLayout.LayoutParams(dp(87), dp(44)));
        create.setOnClickListener(v -> {
            if (archiveBusy) return;
            newChat();
            dialog.dismiss();
        });

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("搜索会话");
        search.setHintTextColor(MUTED);
        search.setTextColor(INK);
        search.setPadding(dp(15), dp(9), dp(15), dp(9));
        search.setBackground(box(SURFACE, 16, BORDER, 1));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(49));
        searchParams.setMargins(dp(15), dp(5), dp(15), dp(13));
        root.addView(search, searchParams);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        Runnable refresh = () -> renderConversationList(list, search.getText().toString(), dialog);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refresh.run();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        refresh.run();
        root.requestFocus();
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.LEFT | Gravity.TOP);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.setLayout(Math.min(dp(410), (int) (getResources().getDisplayMetrics().widthPixels * .9)), -1);
            window.setDimAmount(.3f);
        }
    }

    private void renderConversationList(LinearLayout list, String query, Dialog dialog) {
        list.removeAllViews();
        List<ConversationStore.Conversation> chats = store.search(query);
        if (chats.isEmpty()) {
            TextView message = label("没有找到会话", 14, MUTED, false);
            message.setGravity(Gravity.CENTER);
            list.addView(message, new LinearLayout.LayoutParams(-1, dp(100)));
            return;
        }
        for (ConversationStore.Conversation chat : chats) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(7), dp(5), dp(7));
            row.setBackground(pressable(box(chat.id == currentId ? SAND : Color.TRANSPARENT,
                    12, 0, 0)));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(5);
            list.addView(row, rowParams);
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView title = label(chat.pinned ? "置顶 · " + chat.title : chat.title, 15, INK, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(title);
            String preview = chat.preview.isEmpty() ? "还没有消息" :
                    chat.preview.replace('\n', ' ').replace('\r', ' ');
            TextView detail = label(preview, 11, MUTED, false);
            detail.setSingleLine(true);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = dp(5);
            text.addView(detail, detailParams);
            TextView more = headerButton("⋯", 21);
            more.setContentDescription("会话操作");
            row.addView(more, new LinearLayout.LayoutParams(dp(43), dp(44)));
            row.setOnClickListener(v -> {
                if (archiveBusy) return;
                selectChat(chat.id);
                dialog.dismiss();
            });
            more.setOnClickListener(v -> showConversationActions(chat.id,
                    () -> renderConversationList(list, query, dialog)));
        }
    }

    private void showConversationActions(long chatId, Runnable refresh) {
        ConversationStore.Conversation chat = store.getChat(chatId);
        if (chat == null) return;
        new AlertDialog.Builder(this)
                .setTitle(chat.title)
                .setItems(new String[]{chat.pinned ? "取消置顶" : "置顶会话", "重命名", "删除会话"},
                        (dialog, index) -> {
                            if (archiveBusy) return;
                            if (index == 0) {
                                store.setPinned(chatId, !chat.pinned);
                                refresh.run();
                            } else if (index == 1) renameChat(chatId, refresh);
                            else deleteChat(chatId, refresh);
                        }).show();
    }

    private void showChatMenu() {
        Dialog sheet = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(22));
        panel.setBackground(box(SURFACE, 23, 0, 0));
        sheet.setContentView(panel);
        TextView title = label("会话设置", 19, INK, true);
        title.setPadding(0, 0, 0, dp(12));
        panel.addView(title);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(items);
        String[] names = {"选择模型", "编辑系统提示词", "记忆与夜间做梦", "压缩上下文", "查看压缩摘要",
                "重命名会话", "分享会话文本", "清空当前会话", "API Key 设置",
                "导出全部会话", "导入会话备份", "远端文件管理"};
        String[] marks = {"◈", "✎", "☾", "▦", "☷", "✎", "↗", "⌫", "⚙", "⇧", "⇩", "▤"};
        Runnable[] callbacks = {this::showModelPicker, this::editSystemPrompt, this::showMemorySettings,
                this::compressNow, () -> showSummary(store.getChat(currentId)),
                () -> renameChat(currentId, this::renderChat), this::shareChat, this::clearChat, this::showSettings,
                this::chooseBackupExport, this::chooseBackupImport, this::showRemoteFiles};
        for (int i = 0; i < names.length; i++) {
            final int action = i;
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), 0, dp(12), 0);
            item.setBackground(pressable(box(i == 7 ? Color.rgb(255, 246, 242) : PAPER,
                    13, 0, 0)));
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, dp(47));
            itemParams.bottomMargin = dp(6);
            items.addView(item, itemParams);
            TextView mark = label(marks[i], 18, i == 7 ? TERRACOTTA : ACCENT, true);
            mark.setGravity(Gravity.CENTER);
            item.addView(mark, new LinearLayout.LayoutParams(dp(31), -1));
            TextView name = label(names[i], 14, INK, true);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
            nameParams.leftMargin = dp(9);
            item.addView(name, nameParams);
            item.addView(label("›", 21, MUTED, false));
            item.setOnClickListener(v -> {
                sheet.dismiss();
                callbacks[action].run();
            });
        }
        TextView cancel = label("完成", 14, ACCENT, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(pressable(box(SAND, 13, 0, 0)));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, dp(42));
        cancelParams.topMargin = dp(7);
        panel.addView(cancel, cancelParams);
        cancel.setOnClickListener(v -> sheet.dismiss());
        sheet.show();
        Window window = sheet.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(-1, Math.min(dp(590),
                    getResources().getDisplayMetrics().heightPixels - dp(28)));
            window.setDimAmount(0.4f);
        }
    }

    private void showModelPicker() {
        ConversationStore.Conversation chat = store.getChat(currentId);
        if (chat == null) return;
        int selected = "pro".equals(chat.model) ? 2 : ("think".equals(chat.model) ? 1 : 0);
        new AlertDialog.Builder(this)
                .setTitle("选择当前会话的模型")
                .setSingleChoiceItems(new String[]{"DeepSeek Flash · 快速问答",
                        "DeepSeek Flash · 深度思考", "DeepSeek V4 Pro · 深度思考"},
                        selected, (dialog, which) -> {
                    if (which == 2 && hasActiveAttachment(store.messages(currentId),
                            chat.summaryUntilId)) {
                        Toast.makeText(this, "图片会话请使用 DeepSeek Flash", Toast.LENGTH_LONG).show();
                        return;
                    }
                    store.setModel(currentId, which == 2 ? "pro" : (which == 1 ? "think" : "flash"));
                    dialog.dismiss();
                    renderChat();
                }).setNegativeButton("取消", null).show();
    }

    private void editSystemPrompt() {
        ConversationStore.Conversation chat = store.getChat(currentId);
        if (chat == null) return;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(7), dp(20), 0);
        TextView explain = label("这段指令只用于当前会话，会在每次提问时放在最前面。清空后保存将恢复默认提示词。", 12, MUTED, false);
        content.addView(explain);
        EditText input = new EditText(this);
        input.setText(chat.systemPrompt);
        input.setHint("例如：你是一位耐心的中文学习助手，请先给出简短答案，再举例说明。");
        input.setTextSize(14);
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setGravity(Gravity.TOP);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        content.addView(input, params);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("系统提示词")
                .setView(content)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String prompt = input.getText().toString().trim();
                    if (prompt.length() > 6000) {
                        input.setError("请控制在 6000 字以内");
                        return;
                    }
                    store.setSystemPrompt(chat.id, prompt);
                    dialog.dismiss();
                    Toast.makeText(this, "当前会话的系统提示词已保存", Toast.LENGTH_SHORT).show();
                }));
        dialog.show();
    }

    private void renameChat(long chatId, Runnable refresh) {
        ConversationStore.Conversation chat = store.getChat(chatId);
        if (chat == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(chat.title);
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(20), dp(10), dp(20), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("重命名会话")
                .setView(input)
                .setPositiveButton("保存", (d, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) {
                        store.rename(chatId, value);
                        refresh.run();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void deleteChat(long chatId, Runnable refresh) {
        if (archiveBusy) { Toast.makeText(this, "请等备份操作结束", Toast.LENGTH_SHORT).show(); return; }
        if (busy && busyChatId == chatId) {
            Toast.makeText(this, "请等当前回答结束后再删除", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除这个会话？")
                .setMessage("这个会话的消息、系统提示词和压缩摘要都会从本机删除。")
                .setPositiveButton("删除", (d, which) -> {
                    boolean selected = currentId == chatId;
                    store.delete(chatId);
                    memories.discardSamples(chatId);
                    if (selected) {
                        discardPendingImage();
                        discardPendingFile();
                        currentId = store.selectedChat().id;
                        restoreDraft();
                    }
                    refresh.run();
                    renderChat(selected);
                })
                .setNegativeButton("取消", null).show();
    }

    private void clearChat() {
        if (archiveBusy) { Toast.makeText(this, "请等备份操作结束", Toast.LENGTH_SHORT).show(); return; }
        if (busy && busyChatId == currentId) {
            Toast.makeText(this, "请等当前回答结束后再清空", Toast.LENGTH_SHORT).show();
            return;
        }
        long targetId = currentId;
        new AlertDialog.Builder(this)
                .setTitle("清空当前会话？")
                .setMessage("消息与压缩摘要会删除；会话名称和系统提示词会保留。")
                .setPositiveButton("清空", (d, which) -> {
                    discardPendingImage();
                    discardPendingFile();
                    store.clear(targetId);
                    memories.discardSamples(targetId);
                    restoreDraft();
                    errorText = null;
                    renderChat();
                })
                .setNegativeButton("取消", null).show();
    }

    private void showSummary(ConversationStore.Conversation chat) {
        if (chat == null || chat.summary.isEmpty()) {
            Toast.makeText(this, "当前会话还没有压缩摘要", Toast.LENGTH_SHORT).show();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        TextView text = label(chat.summary, 14, INK, false);
        text.setTextIsSelectable(true);
        text.setPadding(dp(20), dp(14), dp(20), dp(14));
        scroll.addView(text);
        new AlertDialog.Builder(this)
                .setTitle("当前会话的上下文摘要")
                .setView(scroll)
                .setPositiveButton("复制", (d, which) -> copyText(chat.summary))
                .setNegativeButton("关闭", null).show();
    }

    private void shareChat() {
        ConversationStore.Conversation chat = store.getChat(currentId);
        if (chat == null) return;
        StringBuilder text = new StringBuilder(chat.title).append("\n\n");
        for (ConversationStore.Message message : store.messages(currentId)) {
            text.append("user".equals(message.role) ? "我：" : "小鲸：")
                    .append(message.imagePath == null ? "" : "[图片] ")
                    .append(message.content).append("\n\n");
        }
        shareText(text.toString(), "分享会话文本");
    }

    private void shareText(String text, String chooserTitle) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, chooserTitle));
    }

    private void showMemorySettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(10));
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("记忆与夜间做梦")
                .setView(scroll)
                .setPositiveButton("完成", null)
                .create();
        SharedPreferences.OnSharedPreferenceChangeListener refresh = (preferences, key) -> {
            if (dialog.isShowing() && !"samples".equals(key) && !"revision".equals(key)) renderMemorySettings(content);
        };
        dialog.setOnDismissListener(ignored -> memories.unwatch(refresh));
        renderMemorySettings(content);
        dialog.show();
        memories.watch(refresh);
    }

    private void renderMemorySettings(LinearLayout content) {
        content.removeAllViews();
        Switch use = new Switch(this);
        use.setText("跨会话记忆");
        use.setTextSize(16);
        use.setMinHeight(dp(50));
        use.setChecked(memories.enabled());
        content.addView(use, new LinearLayout.LayoutParams(-1, -2));
        memoryHint(content, "开启后，已保存的记忆会随提问发送给 DeepSeek。你可以手动添加，或发送「请记住：…」。");
        use.setOnCheckedChangeListener((button, on) -> {
            memories.setEnabled(on);
            NightMemoryJob.sync(this);
        });

        Switch dream = new Switch(this);
        dream.setText("夜间做梦");
        dream.setTextSize(16);
        dream.setMinHeight(dp(50));
        dream.setEnabled(memories.enabled());
        dream.setChecked(memories.nightly());
        content.addView(dream, new LinearLayout.LayoutParams(-1, -2));
        memoryHint(content, "开启后，应用会在本地时间凌晨 2 点后联网整理近期新消息中的长期偏好和事实。整理使用你的 DeepSeek 额度。");
        dream.setOnCheckedChangeListener((button, on) -> {
            memories.setNightly(on);
            if (!NightMemoryJob.sync(this))
                Toast.makeText(this, "系统暂时无法安排夜间整理", Toast.LENGTH_LONG).show();
        });
        if (memories.nightly()) {
            if (memories.lastRun() > 0) {
                String time = android.text.format.DateFormat.format("MM/dd HH:mm", memories.lastRun()).toString();
                memoryHint(content, time + "  " + memories.lastStatus());
            }
            TextView now = actionChip("立即整理");
            now.setTextColor(ACCENT);
            content.addView(now, new LinearLayout.LayoutParams(-2, -2));
            now.setOnClickListener(v -> {
                String status = NightMemoryJob.runSoon(this) ? "系统将在可用时开始整理" : "系统暂时无法开始整理";
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
            });
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        actions.addView(label("已保存的记忆", 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = actionChip("＋ 添加");
        add.setTextColor(ACCENT);
        actions.addView(add);
        add.setOnClickListener(v -> editMemory(null, ""));

        List<MemoryStore.Entry> entries = memories.entries();
        if (entries.isEmpty()) memoryHint(content, "还没有已保存的记忆");
        for (MemoryStore.Entry entry : entries) {
            TextView item = label(entry.text + "\n" + (entry.automatic ? "夜间整理" : "手动保存"), 14, INK, false);
            item.setPadding(dp(11), dp(10), dp(11), dp(10));
            item.setBackground(pressable(box(SAND, 10, 0, 0)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(6);
            content.addView(item, params);
            item.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(entry.text)
                    .setItems(new String[]{"编辑", "删除"}, (selection, which) -> {
                        if (which == 0) editMemory(entry.id, entry.text);
                        else memories.forget(entry.id);
                    }).show());
        }
        if (!entries.isEmpty()) {
            TextView clear = actionChip("清空记忆");
            content.addView(clear, new LinearLayout.LayoutParams(-2, -2));
            clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("清空已保存的记忆？")
                    .setMessage("已保存的记忆和待整理消息摘录会被删除。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空", (ignored, which) -> memories.clear()).show());
        }
    }

    private void memoryHint(LinearLayout content, String text) {
        TextView note = label(text, 13, MUTED, false);
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(0, 0, 0, dp(9));
        content.addView(note);
    }

    private void editMemory(String id, String initial) {
        EditText input = new EditText(this);
        input.setTextSize(16);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setHint("例如，我喜欢用实际例子理解新概念");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(MemoryStore.ENTRY_LIMIT)});
        input.setText(initial);
        input.setSelection(input.length());
        LinearLayout frame = new LinearLayout(this);
        frame.setPadding(dp(20), dp(8), dp(20), 0);
        frame.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(id == null ? "添加记忆" : "编辑记忆")
                .setView(frame)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (!memories.save(id, value)) {
                input.setError(value.isEmpty() ? "请输入记忆内容" : "最多可保存 32 条手动记忆");
                return;
            }
            Toast.makeText(this, "已保存记忆", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void observeMemory(long chatId, String text) {
        if (memories.observe(chatId, text))
            Toast.makeText(this, "已记住，可在记忆设置中管理", Toast.LENGTH_SHORT).show();
    }

    private void showSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(12), dp(22), 0);
        TextView info = label("填写你自己的 DeepSeek API Key。它只会发往 DeepSeek 官方 API，并加密保存在这台手机上。", 13, MUTED, false);
        info.setLineSpacing(dp(3), 1f);
        content.addView(info);
        TextView state;
        try {
            state = label(secrets.read() == null ? "当前：尚未设置" : "当前：已保存 API Key", 12, ACCENT, true);
        } catch (Exception e) {
            state = label("当前：保存的 Key 无法读取，请重新设置", 12,
                    Color.rgb(150, 83, 63), true);
        }
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-1, -2);
        stateParams.topMargin = dp(13);
        content.addView(state, stateParams);
        EditText keyField = new EditText(this);
        keyField.setSingleLine(true);
        keyField.setTextSize(15);
        keyField.setHint("sk-...（输入新 Key）");
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(-1, -2);
        keyParams.topMargin = dp(9);
        content.addView(keyField, keyParams);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("DeepSeek API Key")
                .setView(content)
                .setPositiveButton("保存", null)
                .setNeutralButton("清除 Key", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = keyField.getText().toString().trim();
                if (value.isEmpty() || value.contains(" ") || value.contains("\n")) {
                    keyField.setError("请输入不含空格的 API Key");
                    return;
                }
                try {
                    secrets.save(value);
                    keyConfigured = true;
                    renderChat();
                    Toast.makeText(this, "API Key 已保存", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } catch (Exception e) {
                    Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_LONG).show();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                secrets.clear();
                keyConfigured = false;
                renderChat();
                Toast.makeText(this, "API Key 已清除", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void newChat() {
        if (archiveBusy) {
            Toast.makeText(this, "请等当前操作结束", Toast.LENGTH_SHORT).show();
            return;
        }
        selectChat(store.createChat());
    }

    private void selectChat(long chatId) {
        if (currentId != chatId) {
            store.saveDraft(currentId, composer.getText().toString());
            discardPendingImage();
            discardPendingFile();
            currentId = chatId;
            store.selectChat(chatId);
            restoreDraft();
        }
        renderChat(true);
    }

    private void restoreDraft() {
        composer.setText(store.draft(currentId));
        composer.setSelection(composer.length());
        composer.clearFocus();
    }

    private void editAsBranch(ConversationStore.Message message) {
        if (busy || archiveBusy) return;
        EditText edit = new EditText(this);
        edit.setText(message.content);
        edit.setTextSize(16);
        edit.setMinLines(2);
        edit.setMaxLines(8);
        edit.setSelection(edit.length());
        LinearLayout frame = new LinearLayout(this);
        frame.setPadding(dp(20), dp(8), dp(20), 0);
        frame.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("编辑到新分支")
                .setView(frame)
                .setNegativeButton("取消", null)
                .setPositiveButton("发送", null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String updated = edit.getText().toString().trim();
                    if (updated.isEmpty()) {
                        edit.setError("请输入消息");
                        return;
                    }
                    dialog.dismiss();
                    openBranch(message, updated);
                }));
        dialog.show();
    }

    private void openBranch(ConversationStore.Message message, String editedText) {
        if (archiveBusy || busy) {
            Toast.makeText(this, "请等当前操作结束", Toast.LENGTH_SHORT).show();
            return;
        }
        long sourceId = currentId;
        archiveBusy = true;
        sendButton.setEnabled(false);
        Toast.makeText(this, "正在创建会话分支…", Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            long branchId;
            try {
                branchId = store.branch(sourceId, message.id, editedText);
            } catch (Exception e) {
                branchId = -1;
            }
            long result = branchId;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                archiveBusy = false;
                if (result < 0) {
                    Toast.makeText(this, "无法创建分支，请检查附件和剩余空间。", Toast.LENGTH_LONG).show();
                    renderChat();
                    return;
                }
                selectChat(result);
                if (editedText != null) {
                    observeMemory(result, editedText);
                    requestReply();
                }
            });
        });
    }

    private String keyOrPrompt() {
        try {
            String key = secrets.read();
            if (key != null && !key.isEmpty()) return key;
            showSettings();
            Toast.makeText(this, "先填写 API Key", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            showSettings();
            Toast.makeText(this, "无法读取 API Key，请重新填写", Toast.LENGTH_LONG).show();
        }
        return null;
    }

    private void send() {
        if (busy || archiveBusy) return;
        if (imageLoading || fileLoading) {
            Toast.makeText(this, "请等附件处理完成", Toast.LENGTH_SHORT).show();
            return;
        }
        String question = composer.getText().toString().trim();
        if (question.isEmpty() && pendingImagePath == null && pendingFilePath == null &&
                extraImages.isEmpty() && extraFiles.isEmpty()) return;
        if (keyOrPrompt() == null) return;
        if (question.isEmpty()) question = pendingFilePath == null && extraFiles.isEmpty() ?
                "请描述这些图片。" : "请总结这些文档。";
        JSONArray attachments = new JSONArray();
        try {
            for (String path : extraImages)
                attachments.put(new JSONObject().put("type", "image").put("path", path));
            for (String path : extraFiles)
                attachments.put(new JSONObject().put("type", "file").put("path", path));
        } catch (Exception e) {
            Toast.makeText(this, "附件处理失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        store.addMessage(currentId, "user", question, pendingImagePath, pendingFilePath,
                attachments.length() == 0 ? null : attachments.toString());
        observeMemory(currentId, question);
        pendingImagePath = null;
        pendingFilePath = null;
        extraImages.clear();
        extraFiles.clear();
        updatePendingImage();
        updatePendingFile();
        composer.setText("");
        store.saveDraft(currentId, "");
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(composer.getWindowToken(), 0);
        requestReply();
    }

    private void regenerate() {
        runRequest(false, true);
    }

    private void requestReply() {
        runRequest(false, false);
    }

    private void compressNow() {
        runRequest(true, false);
    }

    private void runRequest(boolean manualCompression, boolean regenerate) {
        if (busy || archiveBusy) return;
        ConversationStore.Conversation selectedChat = store.getChat(currentId);
        if (selectedChat == null) return;
        List<ConversationStore.Message> snapshot = store.messages(currentId);
        int lastIndex = snapshot.size() - 1;
        if (regenerate && (lastIndex < 1 || !"assistant".equals(snapshot.get(lastIndex).role))) return;
        long replacedId = regenerate ? snapshot.get(lastIndex).id : 0;
        List<ConversationStore.Message> history = regenerate ? snapshot.subList(0, lastIndex) : snapshot;
        if (!manualCompression && (history.isEmpty() ||
                !"user".equals(history.get(history.size() - 1).role))) return;
        if (manualCompression && ContextPlanner.nextBatch(history, selectedChat.summaryUntilId, false) == null) {
            Toast.makeText(this, "已保留最新一轮。暂无更早的对话可压缩。", Toast.LENGTH_LONG).show();
            return;
        }
        String apiKey = keyOrPrompt();
        if (apiKey == null) return;
        String memory = manualCompression ? "" : memories.prompt();
        boolean useWeb = selectedChat.webEnabled;
        if (!manualCompression && "pro".equals(selectedChat.model) &&
                (useWeb || hasActiveAttachment(history, selectedChat.summaryUntilId))) {
            store.setModel(currentId, useWeb ? "think" : "flash");
            selectedChat = store.getChat(currentId);
            Toast.makeText(this, "已切换到支持本次联网或附件的 DeepSeek Flash", Toast.LENGTH_LONG).show();
        }
        final ConversationStore.Conversation chat = selectedChat;
        long targetId = currentId;
        busy = true;
        busyChatId = targetId;
        regeneratingId = replacedId;
        partialText = "";
        DeepSeekClient.Control control = new DeepSeekClient.Control();
        activeControl = control;
        errorText = null;
        errorChatId = -1;
        retryAction = null;
        boolean shouldCompress = manualCompression || ChatRequest.shouldSummarize(chat, history);
        progressText = shouldCompress ? "正在整理较早的对话…" : "正在生成回复…";
        renderChat(!manualCompression);

        worker.execute(() -> {
            DeepSeekClient.DocumentResolver documents = (message, path, extraIndex) -> {
                if (extraIndex < 0 && message.fileId != null && !message.fileId.isEmpty()) return message.fileId;
                JSONArray extras = ConversationStore.extras(message);
                if (extraIndex >= 0) {
                    JSONObject item = extras.optJSONObject(extraIndex);
                    if (item != null && item.optString("file_id", "").startsWith("file-api-"))
                        return item.optString("file_id");
                }
                runOnUiThread(() -> {
                    progressText = "正在上传并解析文档…";
                    if (!isDestroyed() && currentId == targetId) renderChat(false);
                });
                String id = DeepSeekClient.uploadFile(apiKey, path, control);
                if (extraIndex < 0) {
                    message.fileId = id;
                    store.setFileId(message.id, id);
                } else {
                    JSONObject item = extras.getJSONObject(extraIndex);
                    item.put("file_id", id);
                    message.extraAttachments = extras.toString();
                    store.setExtraAttachments(message.id, message.extraAttachments);
                }
                return id;
            };
            final long[] lastStreamUpdate = {0};
            DeepSeekClient.Progress showText = accumulated -> {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastStreamUpdate[0] < 70) return;
                lastStreamUpdate[0] = now;
                runOnUiThread(() -> {
                    if (isDestroyed() || !busy || busyChatId != targetId || control.isCancelled()) return;
                    partialText = accumulated;
                    if (currentId == targetId) {
                        if (streamingBubble == null) renderChat(isNearBottom());
                        else {
                            boolean follow = isNearBottom();
                            streamingBubble.setText(accumulated);
                            if (follow) scrollToBottom();
                        }
                    }
                });
            };
            DeepSeekClient.ToolProgress showStatus = status -> runOnUiThread(() -> {
                if (isDestroyed() || !busy || busyChatId != targetId || control.isCancelled()) return;
                progressText = status;
                if (currentId == targetId && streamingBubble == null) renderChat(false);
            });
            ChatRequest.Outcome outcome = ChatRequest.run(apiKey, chat, history, manualCompression, memory,
                    documents, control, showText, showStatus);
            DeepSeekClient.Reply result = outcome.reply;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                activeControl = null;
                partialText = null;
                if (store.getChat(targetId) == null ||
                        !sameMessages(snapshot, store.messages(targetId))) {
                    renderChat();
                    return;
                }
                boolean cancelled = control.isCancelled();
                if (!cancelled && outcome.throughId != chat.summaryUntilId)
                    store.updateSummary(targetId, outcome.summary, outcome.throughId);
                boolean replySaved = result != null && !result.text.isEmpty() && (!cancelled || replacedId == 0);
                if (replySaved) store.saveReply(targetId, replacedId, result);
                if (cancelled || outcome.failure != null) {
                    errorChatId = targetId;
                    errorText = cancelled
                            ? (replySaved ? "已停止生成，当前回复已保留。" : "已停止。")
                            : friendlyError(outcome.failure);
                    if (!manualCompression && !replySaved)
                        retryAction = () -> runRequest(false, regenerate);
                    else if (manualCompression && !history.isEmpty() &&
                            "user".equals(history.get(history.size() - 1).role))
                        retryAction = this::requestReply;
                } else if (manualCompression) {
                    String notice = outcome.throughId == chat.summaryUntilId
                            ? "摘要没有缩短上下文，已保留原文。" : "较早对话已整理为摘要";
                    Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
                } else if (result != null && result.truncated) {
                    errorChatId = targetId;
                    errorText = "回复达到生成上限。可发送「继续」接着问。";
                }
                if (outcome.omittedAttachments && !cancelled)
                    Toast.makeText(this, "较早附件仍保存在聊天记录中，未纳入本次摘要。", Toast.LENGTH_LONG).show();
                if (currentId == targetId) renderChat(!manualCompression && isNearBottom());
                else {
                    renderChat(false);
                    if (replySaved) Toast.makeText(this, "另一会话已收到回复", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private boolean sameMessages(List<ConversationStore.Message> before,
                                 List<ConversationStore.Message> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).id != after.get(i).id) return false;
        }
        return true;
    }

    private boolean hasActiveAttachment(List<ConversationStore.Message> all, long untilId) {
        for (ConversationStore.Message message : all) {
            if (message.id > untilId && ((message.imagePath != null &&
                    !message.imagePath.isEmpty()) || message.filePath != null ||
                    ConversationStore.extras(message).length() > 0)) return true;
        }
        return false;
    }

    private void scrollToBottom() {
        chatScroll.post(() -> chatScroll.scrollTo(0, chatColumn.getHeight()));
    }

    private boolean isNearBottom() {
        return chatScroll.getScrollY() + chatScroll.getHeight() >=
                chatColumn.getHeight() - dp(80);
    }

    private void stopRequest() {
        DeepSeekClient.Control control = activeControl;
        if (control != null) {
            control.cancel();
            progressText = "正在停止…";
            renderChat();
        }
    }

    private String friendlyError(Exception e) {
        String value = e.getMessage();
        if (value == null) return "连接失败，请检查网络后重试。";
        if (value.contains("Unable to resolve host") || value.contains("UnknownHost")
                || value.contains("ConnectException") || value.contains("timed out")
                || value.contains("SSL")) return "连接失败，请检查网络后重试。";
        if (value.startsWith("DeepSeek") || value.startsWith("API Key") ||
                value.startsWith("请求") || value.startsWith("回复内容") ||
                value.startsWith("图片") || value.startsWith("文档") || value.startsWith("V4 Pro")) return value.trim();
        return "请求失败，请稍后重试。";
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择要发送的图片"), IMAGE_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "手机上没有可用的图片选择器", Toast.LENGTH_LONG).show();
        }
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择文档"), FILE_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "手机上没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    private void chooseBackupExport() {
        if (busy || archiveBusy) { Toast.makeText(this, "请等当前操作结束", Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "像素鲸语-会话备份.zip");
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    private void chooseBackupImport() {
        if (busy || archiveBusy) { Toast.makeText(this, "请等当前操作结束", Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, IMPORT_REQUEST);
    }

    private void showRemoteFiles() {
        if (busy || archiveBusy) {
            Toast.makeText(this, "请等当前操作结束", Toast.LENGTH_SHORT).show();
            return;
        }
        String apiKey = keyOrPrompt();
        if (apiKey == null) return;
        Toast.makeText(this, "正在读取远端文件…", Toast.LENGTH_SHORT).show();
        imageWorker.execute(() -> {
            JSONArray files = null;
            String error = null;
            try { files = DeepSeekClient.listFiles(apiKey); }
            catch (Exception e) { error = friendlyError(e); }
            JSONArray result = files;
            String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (failure != null) { Toast.makeText(this, failure, Toast.LENGTH_LONG).show(); return; }
                if (result == null || result.length() == 0) {
                    new AlertDialog.Builder(this).setTitle("远端文件")
                            .setMessage("当前 DeepSeek 账户没有已上传的文件。")
                            .setPositiveButton("关闭", null).show();
                    return;
                }
                String[] names = new String[result.length()];
                for (int i = 0; i < names.length; i++) {
                    JSONObject file = result.optJSONObject(i);
                    names[i] = file == null ? "未知文件" : file.optString("filename", "未命名文件") +
                            "  ·  " + (file.optLong("bytes") / 1024) + " KB";
                }
                new AlertDialog.Builder(this)
                        .setTitle("账户远端文件 · 最近 " + result.length() + " 项")
                        .setItems(names, (dialog, index) -> {
                            JSONObject file = result.optJSONObject(index);
                            if (file == null) return;
                            String id = file.optString("id", "");
                            new AlertDialog.Builder(this).setTitle("删除远端文件？")
                                    .setMessage(file.optString("filename", id) +
                                            "\n删除后，其他客户端也无法继续引用这个文件。")
                                    .setPositiveButton("删除", (d, which) -> imageWorker.execute(() -> {
                                        String problem = null;
                                        try {
                                            DeepSeekClient.deleteFile(apiKey, id);
                                            store.forgetRemoteFile(id);
                                        } catch (Exception e) { problem = friendlyError(e); }
                                        String finalProblem = problem;
                                        runOnUiThread(() -> {
                                            if (isDestroyed()) return;
                                            Toast.makeText(this, finalProblem == null ?
                                                    "远端文件已删除" : finalProblem, Toast.LENGTH_LONG).show();
                                            if (finalProblem == null) showRemoteFiles();
                                        });
                                    }))
                                    .setNegativeButton("取消", null).show();
                        }).setNegativeButton("关闭", null).show();
            });
        });
    }

    private void updatePendingImage() {
        boolean visible = imageLoading || pendingImagePath != null || !extraImages.isEmpty();
        pendingImageRow.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        pendingImageLabel.setText(imageLoading ? "正在处理图片…" :
                "已选 " + (extraImages.size() + (pendingImagePath == null ? 0 : 1)) + " 张图片 · 可继续添加");
        if (imageLoading || pendingImagePath == null) {
            pendingImagePreview.setImageDrawable(null);
        } else {
            showThumbnail(pendingImagePreview, pendingImagePath, 200);
        }
    }

    private void discardPendingImage() {
        imageSelectionId++;
        String previous = pendingImagePath;
        pendingImagePath = null;
        imageLoading = false;
        if (previous != null) ImageStore.delete(previous);
        for (String path : extraImages) ImageStore.delete(path);
        extraImages.clear();
        if (pendingImageRow != null) updatePendingImage();
    }

    private void discardPendingFile() {
        fileSelectionId++;
        fileLoading = false;
        FileStore.delete(pendingFilePath);
        for (String path : extraFiles) FileStore.delete(path);
        extraFiles.clear();
        pendingFilePath = null;
        updatePendingFile();
    }

    private void showThumbnail(ImageView view, String path, int maxPixels) {
        view.setTag(path);
        String cacheKey = path + "#" + maxPixels;
        Bitmap cached = imageCache.get(cacheKey);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        thumbnailWorker.execute(() -> {
            Bitmap loaded = ImageStore.thumbnail(path, maxPixels);
            runOnUiThread(() -> {
                if (isDestroyed() || loaded == null || !path.equals(view.getTag())) {
                    if (loaded != null) loaded.recycle();
                    return;
                }
                imageCache.put(cacheKey, loaded);
                view.setImageBitmap(loaded);
            });
        });
    }

    private void showFullImage(String path) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(INK);
        ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        final Bitmap[] shown = {null};
        dialog.setOnDismissListener(ignored -> {
            picture.setImageDrawable(null);
            if (shown[0] != null) shown[0].recycle();
        });
        TextView close = label("关闭 ×", 15, SURFACE, true);
        close.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(80), dp(48),
                Gravity.TOP | Gravity.RIGHT);
        closeParams.topMargin = dp(12);
        closeParams.rightMargin = dp(12);
        frame.addView(close, closeParams);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(frame);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(-1, -1);
        thumbnailWorker.execute(() -> {
            Bitmap full = ImageStore.thumbnail(path, 1500);
            runOnUiThread(() -> {
                if (isDestroyed() || !dialog.isShowing()) {
                    if (full != null) full.recycle();
                    return;
                }
                shown[0] = full;
                picture.setImageBitmap(full);
            });
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == EXPORT_REQUEST || requestCode == IMPORT_REQUEST) &&
                resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            boolean exporting = requestCode == EXPORT_REQUEST;
            archiveBusy = true;
            renderChat();
            Toast.makeText(this, exporting ? "正在导出会话…" : "正在导入会话…", Toast.LENGTH_SHORT).show();
            imageWorker.execute(() -> {
                String error = null;
                long importedId = -1;
                try {
                    if (exporting) {
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out == null) throw new Exception("无法写入备份文件。" );
                            ConversationBackup.exportArchive(store, out);
                        }
                    } else {
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            if (in == null) throw new Exception("无法读取备份文件。" );
                            importedId = ConversationBackup.importArchive(getApplicationContext(), store, in);
                        }
                    }
                } catch (Exception e) { error = e.getMessage(); }
                String finalError = error;
                long finalId = importedId;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    archiveBusy = false;
                    if (finalError == null) {
                        if (!exporting && finalId > 0) selectChat(finalId);
                        Toast.makeText(this, exporting ? "会话备份已导出" : "会话已导入", Toast.LENGTH_LONG).show();
                    } else Toast.makeText(this, finalError, Toast.LENGTH_LONG).show();
                    if (exporting || finalId <= 0 || finalError != null) renderChat();
                });
            });
        }
        if (requestCode == FILE_REQUEST && resultCode == RESULT_OK && data != null) {
            List<Uri> uris = selectedUris(data);
            if (uris.isEmpty()) return;
            if (uris.size() + extraFiles.size() + (pendingFilePath == null ? 0 : 1) > 20) {
                Toast.makeText(this, "一条消息最多添加 20 份文档", Toast.LENGTH_LONG).show(); return;
            }
            fileLoading = true;
            int selection = ++fileSelectionId;
            pendingFileLabel.setText("正在处理文档…");
            pendingFileLabel.setVisibility(View.VISIBLE);
            imageWorker.execute(() -> {
                List<String> saved = new ArrayList<>();
                String failure = null;
                try { for (Uri uri : uris) saved.add(FileStore.save(getApplicationContext(), uri)); }
                catch (Exception e) { failure = e.getMessage(); }
                String error = failure;
                runOnUiThread(() -> {
                    if (isDestroyed() || selection != fileSelectionId) {
                        for (String path : saved) FileStore.delete(path);
                        return;
                    }
                    fileLoading = false;
                    for (String path : saved) {
                        if (pendingFilePath != null) extraFiles.add(pendingFilePath);
                        pendingFilePath = path;
                    }
                    updatePendingFile();
                    if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                });
            });
        }
        if (requestCode == IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            List<Uri> uris = selectedUris(data);
            if (uris.isEmpty()) return;
            if (uris.size() + extraImages.size() + (pendingImagePath == null ? 0 : 1) > 20) {
                Toast.makeText(this, "一条消息最多添加 20 张图片", Toast.LENGTH_LONG).show(); return;
            }
            int selection = ++imageSelectionId;
            imageLoading = true;
            updatePendingImage();
            imageWorker.execute(() -> {
                List<String> saved = new ArrayList<>();
                String failure = null;
                try {
                    for (Uri uri : uris) saved.add(ImageStore.saveFromUri(getApplicationContext(), uri));
                } catch (Exception e) {
                    failure = e.getMessage() == null ? "图片处理失败，请重试。" : e.getMessage();
                }
                String finalFailure = failure;
                runOnUiThread(() -> {
                    if (isDestroyed() || selection != imageSelectionId) {
                        for (String path : saved) ImageStore.delete(path);
                        return;
                    }
                    for (String path : saved) {
                        if (pendingImagePath != null) extraImages.add(pendingImagePath);
                        pendingImagePath = path;
                    }
                    if (finalFailure != null) {
                        Toast.makeText(this, finalFailure, Toast.LENGTH_LONG).show();
                    }
                    imageLoading = false;
                    updatePendingImage();
                });
            });
        }
    }

    private List<Uri> selectedUris(Intent data) {
        List<Uri> result = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null) result.add(uri);
        }
        if (result.isEmpty() && data.getData() != null) result.add(data.getData());
        return result;
    }

    @Override protected void onPause() {
        if (composer != null) store.saveDraft(currentId, composer.getText().toString());
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (activeControl != null) activeControl.cancel();
        discardPendingImage();
        discardPendingFile();
        worker.shutdown();
        imageWorker.shutdown();
        thumbnailWorker.shutdown();
        store.close();
        super.onDestroy();
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private RippleDrawable pressable(GradientDrawable content) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(34, 129, 76, 52)),
                content, null);
    }

    private GradientDrawable box(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
