package com.pixelwhale.deepseek;

import java.util.ArrayList;
import java.util.List;

public final class ContextPlannerTest {
    public static void main(String[] args) {
        List<ConversationStore.Message> chat = new ArrayList<>();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 2000; i++) longText.append('鲸');
        for (int i = 1; i <= 12; i++) {
            chat.add(new ConversationStore.Message(i, i % 2 == 0 ? "assistant" : "user",
                    longText.toString()));
        }
        if (ContextPlanner.estimatedTokens(chat, "") >= ContextPlanner.AUTO_THRESHOLD)
            throw new AssertionError("A few thousand tokens should not trigger compression");
        List<ConversationStore.Message> longChat = new ArrayList<>();
        for (int i = 0; i < 200; i++) longChat.add(chat.get(0));
        if (ContextPlanner.estimatedTokens(longChat, "") <= ContextPlanner.AUTO_THRESHOLD)
            throw new AssertionError("A large context should trigger proactive compression");

        ContextPlanner.Batch batch = ContextPlanner.nextBatch(chat, 0, true);
        if (batch == null || batch.throughId != 4 || batch.messages.size() != 4)
            throw new AssertionError("Compression must keep the newest eight messages");
        List<ConversationStore.Message> active = ContextPlanner.active(chat, batch.throughId);
        if (active.size() != 8 || active.get(0).id != 5 || active.get(7).id != 12)
            throw new AssertionError("The summary boundary must exclude only summarized messages");
        if (ContextPlanner.nextBatch(chat, batch.throughId, true) != null)
            throw new AssertionError("Recent messages must not be summarized again");

        List<ConversationStore.Message> otherChat = new ArrayList<>();
        otherChat.add(new ConversationStore.Message(100, "user", "独立会话"));
        if (ContextPlanner.active(otherChat, 0).size() != 1)
            throw new AssertionError("Other chats must retain their own context");
        int textOnly = ContextPlanner.estimatedTokens(otherChat, "");
        List<ConversationStore.Message> withImage = new ArrayList<>();
        withImage.add(new ConversationStore.Message(101, "user", "独立会话", "private-image.jpg"));
        if (ContextPlanner.estimatedTokens(withImage, "") - textOnly != 1024)
            throw new AssertionError("An attached image must count toward context pressure");
        List<ConversationStore.Message> shortChat = chat.subList(0, 5);
        if (ContextPlanner.nextBatch(shortChat, 0, true) != null)
            throw new AssertionError("Proactive compression should keep several recent turns");
        ContextPlanner.Batch manual = ContextPlanner.nextBatch(shortChat, 0, false);
        if (manual == null || manual.throughId != 4)
            throw new AssertionError("Manual compression should work with fewer than 10 messages");
        if (!ContextPlanner.olderTurns(shortChat, 4).isEmpty())
            throw new AssertionError("The latest question must survive limit recovery");
        if (ContextPlanner.olderTurns(chat, 0).size() != 10)
            throw new AssertionError("Limit recovery must keep the latest complete turn together");
        System.out.println("ContextPlannerTest passed");
    }
}
