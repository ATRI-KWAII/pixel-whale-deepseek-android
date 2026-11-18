package com.pixelwhale.deepseek;

import java.util.ArrayList;
import java.util.List;

/** Chooses old messages to summarize while keeping the recent conversation verbatim. */
final class ContextPlanner {
    static final int AUTO_THRESHOLD = 10000;
    private static final int BATCH_TARGET = 7000;
    private static final int KEEP_RECENT = 8;

    static final class Batch {
        final List<ConversationStore.Message> messages;
        final long throughId;
        Batch(List<ConversationStore.Message> messages, long throughId) {
            this.messages = messages;
            this.throughId = throughId;
        }
    }

    static List<ConversationStore.Message> active(List<ConversationStore.Message> all, long untilId) {
        List<ConversationStore.Message> result = new ArrayList<>();
        for (ConversationStore.Message message : all) {
            if (message.id > untilId) result.add(message);
        }
        return result;
    }

    static int estimatedTokens(List<ConversationStore.Message> messages, String summary) {
        int count = estimate(summary == null ? "" : summary);
        for (ConversationStore.Message message : messages) count += cost(message);
        return count;
    }

    static Batch nextBatch(List<ConversationStore.Message> all, long untilId) {
        List<ConversationStore.Message> pending = active(all, untilId);
        if (pending.size() <= KEEP_RECENT + 1) return null;
        int eligible = pending.size() - KEEP_RECENT;
        List<ConversationStore.Message> segment = new ArrayList<>();
        int budget = 0;
        for (int i = 0; i < eligible; i++) {
            ConversationStore.Message message = pending.get(i);
            segment.add(message);
            budget += cost(message);
            if (budget >= BATCH_TARGET && segment.size() >= 2) break;
        }
        return segment.size() < 2 ? null : new Batch(segment,
                segment.get(segment.size() - 1).id);
    }

    private static int estimate(String value) {
        int tokens = 0;
        int western = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x2E80) tokens++;
            else western++;
        }
        return tokens + (western + 3) / 4;
    }

    private static int cost(ConversationStore.Message message) {
        return estimate(message.content) + 8 +
                (message.imagePath == null || message.imagePath.isEmpty() ? 0 : 1024);
    }
}
