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
        if (ContextPlanner.estimatedTokens(chat, "") <= ContextPlanner.AUTO_THRESHOLD)
            throw new AssertionError("Long chat should trigger compression");
        ContextPlanner.Batch batch = ContextPlanner.nextBatch(chat, 0);
        if (batch == null || batch.throughId != 4 || batch.messages.size() != 4)
            throw new AssertionError("Compression must keep the newest eight messages");
        List<ConversationStore.Message> active = ContextPlanner.active(chat, batch.throughId);
        if (active.size() != 8 || active.get(0).id != 5 || active.get(7).id != 12)
            throw new AssertionError("The summary boundary must exclude only summarized messages");
        if (ContextPlanner.nextBatch(chat, batch.throughId) != null)
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
        System.out.println("ContextPlannerTest passed");
    }
}
