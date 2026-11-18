package com.pixelwhale.deepseek;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.LruCache;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int NAVY = Color.rgb(24, 59, 88);
    private static final int BLUE = Color.rgb(38, 123, 155);
    private static final int PALE = Color.rgb(242, 248, 247);
    private static final int MUTED = Color.rgb(110, 137, 150);
    private static final int WHITE = Color.WHITE;
    private static final int BORDER = Color.rgb(204, 225, 228);
    private static final int CORAL = Color.rgb(244, 145, 121);
    private static final int MINT = Color.rgb(222, 244, 243);
    private static final int IMAGE_REQUEST = 18;
    private static final int FILE_REQUEST = 19;
    private static final int PAGE_SIZE = 40;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailWorker = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private SecretStore secrets;
    private ConversationStore store;
    private long currentId;
    private long busyChatId = -1;
    private long errorChatId = -1;
    private boolean busy;
    private boolean errorRetryable;
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
    private TextView pendingFileLabel;
    private boolean imageLoading;
    private boolean fileLoading;
    private int imageSelectionId;
    private int visibleMessageCount = PAGE_SIZE;
    private long renderedChatId = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setNavigationBarColor(PALE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        secrets = new SecretStore(this);
        store = new ConversationStore(this);
        webEnabled = getPreferences(MODE_PRIVATE).getBoolean("web_enabled", false);
        currentId = store.selectedChat().id;
        buildScreen();
        renderChat(true);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PALE);
        setContentView(root);

        FrameLayout headerFrame = new FrameLayout(this);
        root.addView(headerFrame, new LinearLayout.LayoutParams(-1, dp(84)));
        headerFrame.addView(new OceanBackdropView(this),
                new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(16));
        headerFrame.addView(header, new FrameLayout.LayoutParams(-1, -1));

        TextView menu = headerButton("☰", 20);
        header.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(42)));
        menu.setOnClickListener(v -> showConversationList());

        FrameLayout avatar = new FrameLayout(this);
        avatar.setBackground(box(Color.rgb(225, 247, 248), 14, 0, 0));
        avatar.setElevation(dp(2));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(45), dp(45));
        avatarParams.leftMargin = dp(8);
        header.addView(avatar, avatarParams);
        avatar.addView(new PixelWhaleView(this), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        headingParams.leftMargin = dp(12);
        header.addView(heading, headingParams);
        titleView = label("新对话", 18, WHITE, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(titleView);
        TextView brand = label("PIXEL WHALE  ·  DEEPSEEK", 9,
                Color.rgb(184, 225, 233), true);
        brand.setLetterSpacing(0.08f);
        heading.addView(brand);

        TextView add = headerButton("＋", 24);
        add.setBackground(pressable(box(CORAL, 12, 0, 0)));
        header.addView(add, new LinearLayout.LayoutParams(dp(39), dp(42)));
        add.setOnClickListener(v -> newChat());
        TextView more = headerButton("⋯", 25);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(39), dp(42));
        moreParams.leftMargin = dp(3);
        header.addView(more, moreParams);
        more.setOnClickListener(v -> showChatMenu());

        LinearLayout stripe = new LinearLayout(this);
        stripe.setGravity(Gravity.CENTER_VERTICAL);
        stripe.setPadding(dp(16), dp(8), dp(16), dp(7));
        stripe.setBackgroundColor(PALE);
        root.addView(stripe, new LinearLayout.LayoutParams(-1, dp(53)));
        modelView = label("DEEPSEEK CHAT", 11, BLUE, true);
        modelView.setLetterSpacing(0.03f);
        modelView.setGravity(Gravity.CENTER_VERTICAL);
        modelView.setSingleLine(true);
        modelView.setEllipsize(TextUtils.TruncateAt.END);
        modelView.setPadding(dp(13), 0, dp(12), 0);
        modelView.setBackground(pressable(box(WHITE, 12, BORDER, 1)));
        stripe.addView(modelView, new LinearLayout.LayoutParams(0, dp(37), 1));
        modelView.setOnClickListener(v -> showModelPicker());
        TextView promptShortcut = label("✦ 提示词", 11, NAVY, true);
        promptShortcut.setGravity(Gravity.CENTER);
        promptShortcut.setBackground(pressable(box(MINT, 12, BORDER, 1)));
        LinearLayout.LayoutParams promptParams = new LinearLayout.LayoutParams(dp(78), dp(37));
        promptParams.leftMargin = dp(8);
        stripe.addView(promptShortcut, promptParams);
        promptShortcut.setOnClickListener(v -> editSystemPrompt());

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatScroll.setClipToPadding(false);
        chatScroll.setVerticalScrollBarEnabled(false);
        root.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        chatColumn = new LinearLayout(this);
        chatColumn.setOrientation(LinearLayout.VERTICAL);
        chatColumn.setPadding(dp(16), dp(18), dp(16), dp(18));
        chatScroll.addView(chatColumn, new ScrollView.LayoutParams(-1, -2));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(15), dp(8), dp(15), dp(12));
        bottom.setBackgroundColor(PALE);
        bottom.setElevation(dp(5));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        pendingImageRow = new LinearLayout(this);
        pendingImageRow.setGravity(Gravity.CENTER_VERTICAL);
        pendingImageRow.setPadding(dp(9), dp(6), dp(9), dp(6));
        pendingImageRow.setBackground(box(MINT, 15, BORDER, 1));
        LinearLayout.LayoutParams pendingParams = new LinearLayout.LayoutParams(-1, dp(68));
        pendingParams.bottomMargin = dp(8);
        bottom.addView(pendingImageRow, pendingParams);
        pendingImagePreview = new ImageView(this);
        pendingImagePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pendingImagePreview.setBackground(box(Color.rgb(232, 244, 247), 8, 0, 0));
        pendingImagePreview.setClipToOutline(true);
        pendingImageRow.addView(pendingImagePreview,
                new LinearLayout.LayoutParams(dp(55), dp(55)));
        pendingImageLabel = label("图片已准备", 13, NAVY, true);
        LinearLayout.LayoutParams imageLabelParams = new LinearLayout.LayoutParams(0, -2, 1);
        imageLabelParams.leftMargin = dp(10);
        pendingImageRow.addView(pendingImageLabel, imageLabelParams);
        TextView removeImage = label("移除 ×", 12, Color.rgb(176, 92, 78), true);
        removeImage.setPadding(dp(8), dp(12), dp(6), dp(12));
        pendingImageRow.addView(removeImage);
        removeImage.setOnClickListener(v -> discardPendingImage());
        pendingImageRow.setVisibility(View.GONE);

        pendingFileLabel = label("", 12, NAVY, true);
        pendingFileLabel.setPadding(dp(12), dp(9), dp(12), dp(9));
        pendingFileLabel.setBackground(pressable(box(MINT, 12, BORDER, 1)));
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(-1, -2);
        fileParams.bottomMargin = dp(7);
        bottom.addView(pendingFileLabel, fileParams);
        pendingFileLabel.setOnClickListener(v -> {
            FileStore.delete(pendingFilePath);
            pendingFilePath = null;
            updatePendingFile();
        });
        updatePendingFile();

        webButton = label("", 12, BLUE, true);
        webButton.setPadding(dp(13), dp(7), dp(13), dp(7));
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(-2, -2);
        webParams.bottomMargin = dp(7);
        bottom.addView(webButton, webParams);
        webButton.setOnClickListener(v -> {
            webEnabled = !webEnabled;
            getPreferences(MODE_PRIVATE).edit().putBoolean("web_enabled", webEnabled).apply();
            updateWebButton();
        });
        updateWebButton();

        LinearLayout composeRow = new LinearLayout(this);
        composeRow.setGravity(Gravity.CENTER_VERTICAL);
        composeRow.setPadding(dp(6), dp(5), dp(6), dp(5));
        composeRow.setBackground(box(WHITE, 21, BORDER, 1));
        composeRow.setElevation(dp(3));
        bottom.addView(composeRow, new LinearLayout.LayoutParams(-1, -2));

        TextView attach = label("＋", 22, BLUE, true);
        attach.setGravity(Gravity.CENTER);
        attach.setBackground(pressable(box(MINT, 14, 0, 0)));
        LinearLayout.LayoutParams attachParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        attachParams.rightMargin = dp(5);
        composeRow.addView(attach, attachParams);
        attach.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(new String[]{"发送图片", "发送文档"}, (dialog, which) -> {
                    if (which == 0) pickImage(); else pickFile();
                }).show());

        composer = new EditText(this);
        composer.setTextSize(15);
        composer.setTextColor(NAVY);
        composer.setHintTextColor(Color.rgb(145, 166, 176));
        composer.setHint("问问小鲸鱼…");
        composer.setMinLines(1);
        composer.setMaxLines(5);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setPadding(dp(8), dp(8), dp(8), dp(8));
        composer.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -2, 1);
        inputParams.rightMargin = dp(3);
        composeRow.addView(composer, inputParams);

        sendButton = label("➤", 20, WHITE, true);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setBackground(pressable(box(CORAL, 15, 0, 0)));
        composeRow.addView(sendButton, new LinearLayout.LayoutParams(dp(43), dp(43)));
        sendButton.setOnClickListener(v -> {
            if (busy) stopRequest();
            else send();
        });
        TextView footnote = label("✦  图片与提问会发送至 DeepSeek  ·  会话保存在本机", 10, MUTED, false);
        footnote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footParams = new LinearLayout.LayoutParams(-1, -2);
        footParams.topMargin = dp(7);
        bottom.addView(footnote, footParams);
    }

    private void updateWebButton() {
        webButton.setText(webEnabled ? "⌕  联网搜索 · 链接读取  开" : "⌕  联网搜索 · 链接读取  关");
        webButton.setBackground(pressable(box(webEnabled ? MINT : WHITE, 12, BORDER, 1)));
    }

    private void updatePendingFile() {
        pendingFileLabel.setVisibility(pendingFilePath == null ? View.GONE : View.VISIBLE);
        if (pendingFilePath != null)
            pendingFileLabel.setText("▤  " + FileStore.name(pendingFilePath) + "  ·  点此移除");
    }

    private void renderChat() { renderChat(false); }

    private void renderChat(boolean scrollBottom) {
        int oldScrollY = chatScroll.getScrollY();
        ConversationStore.Conversation chat = store.getChat(currentId);
        if (chat == null) {
            chat = store.selectedChat();
            currentId = chat.id;
        }
        if (renderedChatId != currentId) {
            visibleMessageCount = PAGE_SIZE;
            renderedChatId = currentId;
            scrollBottom = true;
        }
        titleView.setText(chat.title);
        String modelName = "pro".equals(chat.model) ? "V4 PRO · 深度思考" :
                ("think".equals(chat.model) ? "FLASH · 深度思考" : "FLASH · 快速问答");
        modelView.setText(("◆  " + modelName) +
                (chat.summary.isEmpty() ? "  ·  选择模型" : "  ·  已压缩上下文"));
        chatColumn.removeAllViews();
        streamingBubble = null;
        List<ConversationStore.Message> messages = store.messages(currentId);
        if (messages.isEmpty()) addWelcome();
        else {
            if (!chat.summary.isEmpty()) addSummaryBanner(chat);
            int first = Math.max(0, messages.size() - visibleMessageCount);
            if (first > 0) addLoadOlder();
            for (int i = first; i < messages.size(); i++) {
                addMessage(messages.get(i), i == messages.size() - 1);
            }
        }
        if (busy && busyChatId == currentId) {
            if (partialText == null || partialText.isEmpty())
                addStatus(progressText, false, false);
            else addMessage(new ConversationStore.Message(-1, "assistant", partialText), false);
        }
        if (errorText != null && errorChatId == currentId)
            addStatus(errorText, true, errorRetryable);
        sendButton.setText(busy ? "■" : "➤");
        sendButton.setBackground(pressable(box(busy ? NAVY : CORAL, 15, 0, 0)));
        sendButton.setAlpha(1f);
        if (scrollBottom) chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        else chatScroll.post(() -> chatScroll.scrollTo(0, oldScrollY));
    }

    private void addLoadOlder() {
        TextView older = label("↑  加载更早消息", 12, BLUE, true);
        older.setGravity(Gravity.CENTER);
        older.setBackground(pressable(box(WHITE, 13, BORDER, 1)));
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
        hero.setPadding(dp(12), dp(12), dp(12), dp(22));
        hero.setBackground(box(WHITE, 24, BORDER, 1));
        hero.setElevation(dp(2));
        chatColumn.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout artPanel = new FrameLayout(this);
        artPanel.setBackground(box(MINT, 19, 0, 0));
        artPanel.setClipToOutline(true);
        hero.addView(artPanel, new LinearLayout.LayoutParams(-1, dp(188)));
        OceanBackdropView sea = new OceanBackdropView(this);
        sea.setLight(true);
        artPanel.addView(sea, new FrameLayout.LayoutParams(-1, -1));
        artPanel.addView(new PixelWhaleView(this), new FrameLayout.LayoutParams(-1, -1));
        TextView artTag = label("  PIXEL WHALE  /  001  ", 9, NAVY, true);
        artTag.setLetterSpacing(0.09f);
        artTag.setGravity(Gravity.CENTER);
        artTag.setBackground(box(Color.rgb(244, 252, 251), 8, 0, 0));
        FrameLayout.LayoutParams artTagParams = new FrameLayout.LayoutParams(-2, dp(26),
                Gravity.TOP | Gravity.LEFT);
        artTagParams.leftMargin = dp(13);
        artTagParams.topMargin = dp(13);
        artPanel.addView(artTag, artTagParams);

        TextView welcome = label("嗨，我是小鲸！", 24, NAVY, true);
        LinearLayout.LayoutParams welcomeParams = new LinearLayout.LayoutParams(-2, -2);
        welcomeParams.topMargin = dp(16);
        hero.addView(welcome, welcomeParams);
        TextView description = label("把想法交给海浪，我们一起找到答案。", 13, MUTED, false);
        description.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(5);
        hero.addView(description, descriptionParams);

        TextView sectionTitle = label("✦  从这里出发", 14, NAVY, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(23);
        titleParams.bottomMargin = dp(9);
        chatColumn.addView(sectionTitle, titleParams);
        addSuggestion("01", "学习一点新东西", "用简单的话解释一个知识点");
        addSuggestion("02", "整理我的计划", "帮我制定一周学习计划");
        addSuggestion("03", "来点灵感", "写一段可爱的像素鲸鱼故事");

        TextView tip = label("✦  首次使用？点右上角 ⋯ 填写 API Key", 11, BLUE, true);
        tip.setGravity(Gravity.CENTER);
        tip.setBackground(box(MINT, 12, 0, 0));
        LinearLayout.LayoutParams tipParams = new LinearLayout.LayoutParams(-1, dp(38));
        tipParams.topMargin = dp(13);
        chatColumn.addView(tip, tipParams);
        tip.setOnClickListener(v -> showSettings());
    }

    private void addSuggestion(String number, String title, String prompt) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(11), dp(8), dp(12), dp(8));
        card.setBackground(pressable(box(WHITE, 16, BORDER, 1)));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, dp(70));
        cardParams.bottomMargin = dp(8);
        chatColumn.addView(card, cardParams);
        TextView icon = label(number, 11, BLUE, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(box(MINT, 12, 0, 0));
        card.addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
        textParams.leftMargin = dp(12);
        card.addView(text, textParams);
        text.addView(label(title, 14, NAVY, true));
        TextView detail = label(prompt, 11, MUTED, false);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(3);
        text.addView(detail, detailParams);
        card.addView(label("↗", 19, BLUE, true));
        card.setOnClickListener(v -> {
            composer.setText(prompt);
            composer.setSelection(composer.length());
            composer.requestFocus();
        });
    }

    private void addSummaryBanner(ConversationStore.Conversation chat) {
        TextView banner = label("✦  较早对话已整理为摘要  ·  点击查看", 12, BLUE, true);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(12), dp(10), dp(12), dp(10));
        banner.setBackground(pressable(box(MINT, 13, BORDER, 1)));
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
        if (!user) {
            FrameLayout avatar = new FrameLayout(this);
            avatar.setBackground(box(MINT, 11, BORDER, 1));
            avatar.setElevation(dp(1));
            row.addView(avatar, new LinearLayout.LayoutParams(dp(35), dp(35)));
            avatar.addView(new PixelWhaleView(this), new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-2, -2);
        bodyParams.leftMargin = user ? dp(42) : dp(9);
        bodyParams.rightMargin = user ? 0 : dp(21);
        row.addView(body, bodyParams);
        TextView role = label(user ? "你" : "小鲸  ·  DEEPSEEK", 10,
                user ? MUTED : BLUE, true);
        role.setLetterSpacing(0.04f);
        role.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout.LayoutParams roleParams = new LinearLayout.LayoutParams(-1, -2);
        roleParams.bottomMargin = dp(5);
        body.addView(role, roleParams);
        if (message.imagePath != null && !message.imagePath.isEmpty()) {
            ImageView picture = new ImageView(this);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setBackground(box(Color.rgb(222, 240, 243), 12, 0, 0));
            picture.setClipToOutline(true);
            LinearLayout.LayoutParams pictureParams = new LinearLayout.LayoutParams(
                    dp(190), dp(150));
            pictureParams.bottomMargin = dp(6);
            body.addView(picture, pictureParams);
            showThumbnail(picture, message.imagePath, 420);
            picture.setOnClickListener(v -> showFullImage(message.imagePath));
        }
        if (message.filePath != null) {
            TextView file = label("▤  " + FileStore.name(message.filePath), 12, BLUE, true);
            file.setPadding(dp(10), dp(7), dp(10), dp(7));
            file.setBackground(box(MINT, 10, BORDER, 1));
            body.addView(file, new LinearLayout.LayoutParams(-2, -2));
        }
        String displayContent = message.content + (message.sources == null ? "" : message.sources);
        TextView bubble = label(displayContent, 15, user ? WHITE : NAVY, false);
        if (!user && message.id != -1) bubble.setText(MarkdownText.render(displayContent));
        if (message.id == -1) streamingBubble = bubble;
        bubble.setTextIsSelectable(true);
        if (!user && message.sources != null && !message.sources.isEmpty()) {
            android.text.util.Linkify.addLinks(bubble, android.text.util.Linkify.WEB_URLS);
            bubble.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
        bubble.setLineSpacing(dp(4), 1.02f);
        bubble.setPadding(dp(15), dp(12), dp(15), dp(12));
        GradientDrawable bubbleShape = box(user ? BLUE : WHITE, 17,
                user ? 0 : BORDER, user ? 0 : 1);
        bubbleShape.setCornerRadii(user
                ? new float[]{dp(18),dp(18),dp(6),dp(6),dp(18),dp(18),dp(18),dp(18)}
                : new float[]{dp(6),dp(6),dp(18),dp(18),dp(18),dp(18),dp(18),dp(18)});
        bubble.setBackground(bubbleShape);
        bubble.setElevation(dp(1));
        body.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        if (message.id == -1) return;
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(7), 0, 0);
        body.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        TextView copy = actionChip("复制");
        actions.addView(copy);
        copy.setOnClickListener(v -> copyText(message.content));
        if (!user && last) {
            TextView retry = actionChip("重新生成 ↻");
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, -2);
            retryParams.leftMargin = dp(7);
            actions.addView(retry, retryParams);
            retry.setOnClickListener(v -> regenerate());
        }
        if (user) {
            TextView reuse = actionChip("再次编辑");
            LinearLayout.LayoutParams reuseParams = new LinearLayout.LayoutParams(-2, -2);
            reuseParams.leftMargin = dp(7);
            actions.addView(reuse, reuseParams);
            reuse.setOnClickListener(v -> {
                composer.setText(message.content);
                composer.setSelection(composer.length());
                composer.requestFocus();
            });
        }
    }

    private TextView actionChip(String title) {
        TextView action = label(title, 10, MUTED, true);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(10), dp(5), dp(10), dp(5));
        action.setBackground(pressable(box(Color.rgb(231, 242, 243), 9, 0, 0)));
        return action;
    }

    private void addStatus(String status, boolean failed, boolean retryable) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(11), dp(14), dp(11));
        card.setBackground(box(failed ? Color.rgb(255, 245, 239) : MINT, 16,
                failed ? Color.rgb(237, 189, 169) : BORDER, 1));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        chatColumn.addView(card, params);
        card.addView(label((failed ? "⚠  " : "✦  ") + status, 13,
                failed ? Color.rgb(137, 73, 57) : MUTED, false));
        if (retryable) {
            TextView retry = label("再试一次  →", 13, BLUE, true);
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, -2);
            retryParams.topMargin = dp(8);
            card.addView(retry, retryParams);
            retry.setOnClickListener(v -> requestReply());
        }
    }

    private TextView headerButton(String text, int size) {
        TextView view = label(text, size, WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(pressable(box(Color.rgb(52, 117, 146), 12, 0, 0)));
        return view;
    }

    private void copyText(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("像素鲸语", value));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private void showConversationList() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PALE);
        dialog.setContentView(root);
        FrameLayout topFrame = new FrameLayout(this);
        root.addView(topFrame, new LinearLayout.LayoutParams(-1, dp(88)));
        topFrame.addView(new OceanBackdropView(this),
                new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(13), dp(16), dp(16));
        topFrame.addView(top, new FrameLayout.LayoutParams(-1, -1));
        TextView back = headerButton("‹", 26);
        back.setGravity(Gravity.CENTER);
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(42)));
        back.setOnClickListener(v -> dialog.dismiss());
        LinearLayout headingGroup = new LinearLayout(this);
        headingGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headParams = new LinearLayout.LayoutParams(0, -2, 1);
        headParams.leftMargin = dp(13);
        top.addView(headingGroup, headParams);
        headingGroup.addView(label("所有会话", 20, WHITE, true));
        TextView headingSub = label("YOUR LITTLE OCEAN", 9,
                Color.rgb(184, 225, 233), true);
        headingSub.setLetterSpacing(0.07f);
        headingGroup.addView(headingSub);
        TextView create = label("＋ 新对话", 14, WHITE, true);
        create.setGravity(Gravity.CENTER);
        create.setBackground(pressable(box(CORAL, 13, 0, 0)));
        top.addView(create, new LinearLayout.LayoutParams(dp(92), dp(42)));
        create.setOnClickListener(v -> {
            newChat();
            dialog.dismiss();
        });

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("⌕  搜索会话标题或内容");
        search.setHintTextColor(MUTED);
        search.setTextColor(NAVY);
        search.setPadding(dp(15), dp(9), dp(15), dp(9));
        search.setBackground(box(WHITE, 16, BORDER, 1));
        search.setElevation(dp(1));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(49));
        searchParams.setMargins(dp(16), dp(17), dp(16), dp(9));
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
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1);
            window.setStatusBarColor(Color.rgb(18, 54, 80));
        }
    }

    private void renderConversationList(LinearLayout list, String query, Dialog dialog) {
        list.removeAllViews();
        List<ConversationStore.Conversation> chats = store.search(query);
        if (chats.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            list.addView(empty, new LinearLayout.LayoutParams(-1, dp(220)));
            empty.addView(new PixelWhaleView(this),
                    new LinearLayout.LayoutParams(dp(130), dp(95)));
            TextView message = label("这片海域还没有会话", 14, MUTED, true);
            message.setGravity(Gravity.CENTER);
            empty.addView(message);
            return;
        }
        TextView count = label("✦  " + chats.size() + " 个会话", 11, MUTED, true);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-1, -2);
        countParams.bottomMargin = dp(10);
        list.addView(count, countParams);
        for (ConversationStore.Conversation chat : chats) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(9), dp(10));
            row.setBackground(pressable(box(chat.id == currentId ? MINT : WHITE,
                    17, BORDER, 1)));
            row.setElevation(dp(1));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(84));
            rowParams.bottomMargin = dp(9);
            list.addView(row, rowParams);
            FrameLayout icon = new FrameLayout(this);
            icon.setBackground(box(chat.id == currentId ? WHITE : MINT, 13, 0, 0));
            row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
            icon.addView(new PixelWhaleView(this), new FrameLayout.LayoutParams(-1, -1));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
            textParams.leftMargin = dp(12);
            row.addView(text, textParams);
            TextView title = label(chat.title, 15, NAVY, true);
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
            LinearLayout side = new LinearLayout(this);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(side, new LinearLayout.LayoutParams(dp(48), -1));
            String date = new SimpleDateFormat("MM/dd", Locale.getDefault())
                    .format(new Date(chat.updatedAt));
            TextView dateText = label(date, 10, MUTED, false);
            dateText.setGravity(Gravity.RIGHT);
            side.addView(dateText);
            TextView more = label("⋯", 21, BLUE, true);
            more.setGravity(Gravity.CENTER);
            more.setBackground(pressable(box(Color.TRANSPARENT, 8, 0, 0)));
            side.addView(more, new LinearLayout.LayoutParams(dp(38), dp(36)));
            row.setOnClickListener(v -> {
                discardPendingImage();
                discardPendingFile();
                currentId = chat.id;
                store.selectChat(currentId);
                dialog.dismiss();
                renderChat(true);
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
                .setItems(new String[]{"重命名", "删除会话"}, (dialog, index) -> {
                    if (index == 0) renameChat(chatId, refresh);
                    else deleteChat(chatId, refresh);
                }).show();
    }

    private void showChatMenu() {
        Dialog sheet = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(22));
        panel.setBackground(box(WHITE, 23, 0, 0));
        sheet.setContentView(panel);
        TextView title = label("✦  会话工具", 19, NAVY, true);
        panel.addView(title);
        TextView subtitle = label("让小鲸按照你的方式回答", 12, MUTED, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(3);
        subtitleParams.bottomMargin = dp(14);
        panel.addView(subtitle, subtitleParams);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(items);
        String[] names = {"选择模型", "编辑系统提示词", "压缩上下文", "查看压缩摘要",
                "重命名会话", "分享会话文本", "清空当前会话", "API Key 设置"};
        String[] marks = {"◈", "✎", "▦", "☷", "✎", "↗", "⌫", "⚙"};
        for (int i = 0; i < names.length; i++) {
            final int action = i;
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), 0, dp(12), 0);
            item.setBackground(pressable(box(i == 6 ? Color.rgb(255, 246, 242) : PALE,
                    13, 0, 0)));
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, dp(47));
            itemParams.bottomMargin = dp(6);
            items.addView(item, itemParams);
            TextView mark = label(marks[i], 18, i == 6 ? CORAL : BLUE, true);
            mark.setGravity(Gravity.CENTER);
            item.addView(mark, new LinearLayout.LayoutParams(dp(31), -1));
            TextView name = label(names[i], 14, NAVY, true);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
            nameParams.leftMargin = dp(9);
            item.addView(name, nameParams);
            item.addView(label("›", 21, MUTED, false));
            item.setOnClickListener(v -> {
                sheet.dismiss();
                switch (action) {
                    case 0: showModelPicker(); break;
                    case 1: editSystemPrompt(); break;
                    case 2: compressNow(); break;
                    case 3: showSummary(store.getChat(currentId)); break;
                    case 4: renameChat(currentId, this::renderChat); break;
                    case 5: shareChat(); break;
                    case 6: clearChat(); break;
                    case 7: showSettings(); break;
                    default: break;
                }
            });
        }
        TextView cancel = label("完成", 14, BLUE, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(pressable(box(MINT, 13, 0, 0)));
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
        if (busy && busyChatId == chatId) {
            Toast.makeText(this, "请等当前回答结束后再删除", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除这个会话？")
                .setMessage("这个会话的消息、系统提示词和压缩摘要都会从本机删除。")
                .setPositiveButton("删除", (d, which) -> {
                    if (currentId == chatId) { discardPendingImage(); discardPendingFile(); }
                    store.delete(chatId);
                    if (currentId == chatId) currentId = store.selectedChat().id;
                    refresh.run();
                    renderChat();
                })
                .setNegativeButton("取消", null).show();
    }

    private void clearChat() {
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
        TextView text = label(chat.summary, 14, NAVY, false);
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
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text.toString());
        startActivity(Intent.createChooser(intent, "分享会话文本"));
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
            state = label(secrets.read() == null ? "当前：尚未设置" : "当前：已保存 API Key", 12, BLUE, true);
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
                    Toast.makeText(this, "API Key 已保存", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } catch (Exception e) {
                    Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_LONG).show();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                secrets.clear();
                Toast.makeText(this, "API Key 已清除", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void newChat() {
        discardPendingImage();
        discardPendingFile();
        currentId = store.createChat();
        errorText = null;
        renderChat(true);
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
        if (busy) return;
        if (imageLoading || fileLoading) {
            Toast.makeText(this, "请等附件处理完成", Toast.LENGTH_SHORT).show();
            return;
        }
        String question = composer.getText().toString().trim();
        if (question.isEmpty() && pendingImagePath == null && pendingFilePath == null) return;
        if (keyOrPrompt() == null) return;
        if (question.isEmpty()) question = pendingFilePath == null ? "请描述这张图片。" : "请总结这份文档。";
        store.addMessage(currentId, "user", question, pendingImagePath, pendingFilePath);
        pendingImagePath = null;
        pendingFilePath = null;
        updatePendingImage();
        updatePendingFile();
        composer.setText("");
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(composer.getWindowToken(), 0);
        requestReply();
    }

    private void regenerate() {
        if (busy || keyOrPrompt() == null) return;
        if (store.removeLastAssistant(currentId)) requestReply();
    }

    private void requestReply() {
        runRequest(false);
    }

    private void compressNow() {
        runRequest(true);
    }

    private void runRequest(boolean manualCompression) {
        if (busy) return;
        String apiKey = keyOrPrompt();
        if (apiKey == null) return;
        ConversationStore.Conversation selectedChat = store.getChat(currentId);
        if (selectedChat == null) return;
        List<ConversationStore.Message> snapshot = store.messages(currentId);
        if (!manualCompression && (snapshot.isEmpty() ||
                !"user".equals(snapshot.get(snapshot.size() - 1).role))) return;
        if (!manualCompression && "pro".equals(selectedChat.model) &&
                hasActiveAttachment(snapshot, selectedChat.summaryUntilId)) {
            store.setModel(currentId, "flash");
            selectedChat = store.getChat(currentId);
            Toast.makeText(this, "此会话含图片，已切换到 DeepSeek Flash", Toast.LENGTH_LONG).show();
        }
        if (!manualCompression && webEnabled && "pro".equals(selectedChat.model)) {
            store.setModel(currentId, "think");
            selectedChat = store.getChat(currentId);
            Toast.makeText(this, "联网工具使用 DeepSeek Flash，已切换模型", Toast.LENGTH_LONG).show();
        }
        final ConversationStore.Conversation chat = selectedChat;
        final boolean useWeb = webEnabled;
        if (manualCompression && ContextPlanner.nextBatch(snapshot, chat.summaryUntilId) == null) {
            Toast.makeText(this, "至少需要 10 条消息，才能保留最近对话并压缩较早内容", Toast.LENGTH_LONG).show();
            return;
        }
        long targetId = currentId;
        busy = true;
        busyChatId = targetId;
        partialText = "";
        DeepSeekClient.Control control = new DeepSeekClient.Control();
        activeControl = control;
        errorText = null;
        errorChatId = -1;
        boolean shouldCompress = manualCompression || ContextPlanner.estimatedTokens(
                ContextPlanner.active(snapshot, chat.summaryUntilId), chat.summary) >
                ContextPlanner.AUTO_THRESHOLD;
        progressText = shouldCompress ? "正在压缩较早对话…" : "小鲸鱼正在想答案…";
        renderChat(!manualCompression);

        worker.execute(() -> {
            DeepSeekClient.DocumentResolver documents = message -> {
                if (message.fileId != null && !message.fileId.isEmpty()) return message.fileId;
                runOnUiThread(() -> {
                    progressText = "正在上传并解析文档…";
                    if (!isDestroyed() && currentId == targetId) renderChat(false);
                });
                String id = DeepSeekClient.uploadFile(apiKey, message.filePath, control);
                message.fileId = id;
                store.setFileId(message.id, id);
                return id;
            };
            String summary = chat.summary;
            long until = chat.summaryUntilId;
            DeepSeekClient.Reply reply = null;
            String failure = null;
            final long[] lastStreamUpdate = {0};
            try {
                if (shouldCompress) {
                    ContextPlanner.Batch batch;
                    while ((batch = ContextPlanner.nextBatch(snapshot, until)) != null &&
                            (manualCompression || ContextPlanner.estimatedTokens(
                                    ContextPlanner.active(snapshot, until), summary) >
                                    ContextPlanner.AUTO_THRESHOLD)) {
                        summary = DeepSeekClient.summarize(apiKey, summary, batch.messages, documents, control);
                        until = batch.throughId;
                    }
                }
                if (!manualCompression) {
                    runOnUiThread(() -> {
                        progressText = "小鲸鱼正在想答案…";
                        if (!isDestroyed() && currentId == targetId) renderChat(false);
                    });
                    reply = DeepSeekClient.reply(apiKey, chat.model, useWeb, chat.systemPrompt, summary,
                            ContextPlanner.active(snapshot, until), documents, control, accumulated -> {
                                long now = System.currentTimeMillis();
                                if (now - lastStreamUpdate[0] < 70) return;
                                lastStreamUpdate[0] = now;
                                runOnUiThread(() -> {
                                    if (isDestroyed() || !busy || busyChatId != targetId) return;
                                    partialText = accumulated;
                                    if (currentId == targetId) {
                                        if (streamingBubble == null) renderChat(isNearBottom());
                                        else {
                                            boolean follow = isNearBottom();
                                            streamingBubble.setText(accumulated);
                                            if (follow) chatScroll.post(() ->
                                                    chatScroll.fullScroll(View.FOCUS_DOWN));
                                        }
                                    }
                                });
                            });
                }
            } catch (Exception e) {
                failure = friendlyError(e);
            }
            final String finalSummary = summary;
            final long finalUntil = until;
            final String finalAnswer = reply == null ? null : reply.text;
            final String finalToolCalls = reply == null ? null : reply.serverToolCalls;
            final String finalSources = reply == null ? null : reply.sources;
            final String finalFailure = failure;
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
                if (finalUntil != chat.summaryUntilId)
                    store.updateSummary(targetId, finalSummary, finalUntil);
                if (finalAnswer != null && !finalAnswer.isEmpty())
                    store.addAssistantWithTools(targetId, finalAnswer, finalToolCalls, finalSources);
                if (control.isCancelled()) {
                    errorChatId = targetId;
                    errorText = finalAnswer == null || finalAnswer.isEmpty()
                            ? "已停止生成，可点击重试。" : "已停止生成，已保留当前回复。";
                    errorRetryable = finalAnswer == null || finalAnswer.isEmpty();
                } else if (finalFailure != null) {
                    errorChatId = targetId;
                    errorText = finalFailure;
                    errorRetryable = !manualCompression;
                } else if (manualCompression) {
                    Toast.makeText(this, "较早对话已压缩", Toast.LENGTH_SHORT).show();
                }
                if (currentId == targetId)
                    renderChat(!manualCompression && isNearBottom());
                else if (finalAnswer != null)
                    Toast.makeText(this, "另一个会话已收到回复", Toast.LENGTH_SHORT).show();
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
                    !message.imagePath.isEmpty()) || message.filePath != null)) return true;
        }
        return false;
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
                value.startsWith("图片") || value.startsWith("V4 Pro")) return value.trim();
        return "请求失败，请稍后重试。";
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
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
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择文档"), FILE_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "手机上没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    private void updatePendingImage() {
        boolean visible = imageLoading || pendingImagePath != null;
        pendingImageRow.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        pendingImageLabel.setText(imageLoading ? "正在处理图片…" : "图片已准备 · 可输入问题");
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
        if (pendingImageRow != null) updatePendingImage();
    }

    private void discardPendingFile() {
        FileStore.delete(pendingFilePath);
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
        frame.setBackgroundColor(NAVY);
        ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        final Bitmap[] shown = {null};
        dialog.setOnDismissListener(ignored -> {
            picture.setImageDrawable(null);
            if (shown[0] != null) shown[0].recycle();
        });
        TextView close = label("关闭 ×", 15, WHITE, true);
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
        if (requestCode == FILE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            fileLoading = true;
            pendingFileLabel.setText("正在处理文档…");
            pendingFileLabel.setVisibility(View.VISIBLE);
            imageWorker.execute(() -> {
                String saved = null;
                String failure = null;
                try { saved = FileStore.save(getApplicationContext(), uri); }
                catch (Exception e) { failure = e.getMessage(); }
                String result = saved;
                String error = failure;
                runOnUiThread(() -> {
                    if (isDestroyed()) { FileStore.delete(result); return; }
                    fileLoading = false;
                    if (result != null) {
                        FileStore.delete(pendingFilePath);
                        pendingFilePath = result;
                        updatePendingFile();
                    } else Toast.makeText(this, error == null ? "文档处理失败" : error, Toast.LENGTH_LONG).show();
                });
            });
        }
        if (requestCode == IMAGE_REQUEST && resultCode == RESULT_OK && data != null &&
                data.getData() != null) {
            Uri uri = data.getData();
            int selection = ++imageSelectionId;
            imageLoading = true;
            updatePendingImage();
            imageWorker.execute(() -> {
                String saved = null;
                String failure = null;
                try {
                    saved = ImageStore.saveFromUri(getApplicationContext(), uri);
                } catch (Exception e) {
                    failure = e.getMessage() == null ? "图片处理失败，请重试。" : e.getMessage();
                }
                String finalSaved = saved;
                String finalFailure = failure;
                runOnUiThread(() -> {
                    if (isDestroyed() || selection != imageSelectionId) {
                        ImageStore.delete(finalSaved);
                        return;
                    }
                    if (finalSaved != null) {
                        ImageStore.delete(pendingImagePath);
                        pendingImagePath = finalSaved;
                    } else {
                        Toast.makeText(this, finalFailure, Toast.LENGTH_LONG).show();
                    }
                    imageLoading = false;
                    updatePendingImage();
                });
            });
        }
    }

    @Override protected void onDestroy() {
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
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(45, 40, 135, 159)),
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
