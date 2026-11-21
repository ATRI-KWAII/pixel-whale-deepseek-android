package com.pixelwhale.deepseek;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Chooses old messages to summarize while keeping the recent conversation verbatim. */
final class ContextPlanner {
    // The official Flash and Pro models have a 1M context window. Leave room for
    // estimated media, tools, and generation before frequent summaries become worthwhile.
    static final int AUTO_THRESHOLD = 256_000;
    private static final int BATCH_TARGET = 48_000;
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

    static List<ConversationStore.Message> olderTurns(List<ConversationStore.Message> all, long untilId) {
        List<ConversationStore.Message> pending = active(all, untilId);
        int firstKept = firstKeptMessage(pending, false);
        return pending.subList(0, Math.max(0, firstKept));
    }

    static Batch nextBatch(List<ConversationStore.Message> all, long untilId, boolean keepMore) {
        List<ConversationStore.Message> pending = active(all, untilId);
        int firstKept = firstKeptMessage(pending, keepMore);
        if (firstKept <= 0) return null;

        int budget = 0;
        int end = 0;
        for (int i = 0; i < firstKept; i++) {
            budget += cost(pending.get(i));
            if ("user".equals(pending.get(i + 1).role)) {
                end = i + 1;
                if (budget >= BATCH_TARGET) break;
            }
        }
        return end == 0 ? null : new Batch(new ArrayList<>(pending.subList(0, end)),
                pending.get(end - 1).id);
    }

    private static int firstKeptMessage(List<ConversationStore.Message> pending, boolean keepMore) {
        // Start the preserved history at a user turn. Always keep the latest
        // question and its reply (if any), even if they alone exceed the budget.
        int firstKept = -1;
        for (int i = pending.size() - 1; i >= 0; i--) {
            if ("user".equals(pending.get(i).role)) {
                firstKept = i;
                if (!keepMore || pending.size() - i >= KEEP_RECENT) break;
            }
        }
        return firstKept;
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
        int cost = estimate(message.content) + 8 +
                (message.imagePath == null || message.imagePath.isEmpty() ? 0 : 1024) +
                (message.filePath == null ? 0 : 4096) +
                (message.serverToolCalls == null ? 0 : estimate(message.serverToolCalls)) +
                (message.reasoningContent == null ? 0 : estimate(message.reasoningContent));
        if (message.extraAttachments != null) {
            JSONArray extras = ConversationStore.extras(message);
            for (int i = 0; i < extras.length(); i++) {
                JSONObject item = extras.optJSONObject(i);
                if (item != null) cost += "image".equals(item.optString("type")) ? 1024 : 4096;
            }
        }
        return cost;
    }
}
