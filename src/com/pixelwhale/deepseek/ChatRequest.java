package com.pixelwhale.deepseek;

import java.util.Collections;
import java.util.List;

/** Prepares context and runs one reply, with recovery reserved for confirmed context limits. */
final class ChatRequest {
    static final class Outcome {
        String summary;
        long throughId;
        DeepSeekClient.Reply reply;
        boolean omittedAttachments;
        Exception failure;

        Outcome(ConversationStore.Conversation chat) {
            summary = chat.summary;
            throughId = chat.summaryUntilId;
        }
    }

    static boolean shouldSummarize(ConversationStore.Conversation chat,
                                   List<ConversationStore.Message> history) {
        List<ConversationStore.Message> active = ContextPlanner.active(history, chat.summaryUntilId);
        return ContextPlanner.estimatedTokens(active, chat.systemPrompt + chat.summary)
                > ContextPlanner.AUTO_THRESHOLD
                && ContextPlanner.nextBatch(history, chat.summaryUntilId, true) != null;
    }

    static Outcome run(String key, ConversationStore.Conversation chat, List<ConversationStore.Message> history,
                       boolean manual, String memory, DeepSeekClient.DocumentResolver documents, DeepSeekClient.Control control,
                       DeepSeekClient.Progress progress, DeepSeekClient.ToolProgress status) {
        Outcome result = new Outcome(chat);
        try {
            if (manual || shouldSummarize(chat, history)) {
                ContextPlanner.Batch batch = ContextPlanner.nextBatch(history, result.throughId, !manual);
                if (batch != null) {
                    try {
                        summarize(key, result, batch.messages, documents, control, manual, false);
                    } catch (Exception error) {
                        if (manual || control.isCancelled()) throw error;
                        // A failed proactive summary should not prevent a normal request.
                    }
                }
            }
            if (manual) return result;
            status.onStatus("正在生成回复…");
            try {
                result.reply = reply(key, chat, history, result, memory, documents, control, progress, status);
            } catch (ApiError error) {
                if (!error.contextLimit || control.isCancelled()) throw error;
                List<ConversationStore.Message> older = ContextPlanner.olderTurns(history, result.throughId);
                if (older.isEmpty()) throw error;
                status.onStatus("上下文超限，正在整理较早的对话…");
                summarize(key, result, older, documents, control, true, true);
                status.onStatus("正在接续回复…");
                result.reply = reply(key, chat, history, result, memory, documents, control, progress, status);
            }
        } catch (Exception error) {
            result.failure = error;
        }
        return result;
    }

    private static DeepSeekClient.Reply reply(
            String key, ConversationStore.Conversation chat, List<ConversationStore.Message> history,
            Outcome result, String memory, DeepSeekClient.DocumentResolver documents, DeepSeekClient.Control control,
            DeepSeekClient.Progress progress, DeepSeekClient.ToolProgress status) throws Exception {
        return DeepSeekClient.reply(key, chat.model, chat.webEnabled, chat.systemPrompt + memory, result.summary,
                ContextPlanner.active(history, result.throughId), documents, control, progress, status);
    }

    private static void summarize(String key, Outcome result, List<ConversationStore.Message> older,
                                  DeepSeekClient.DocumentResolver documents, DeepSeekClient.Control control,
                                  boolean allowTextOnly, boolean recovering) throws Exception {
        String merged;
        boolean omitted = false;
        try {
            merged = DeepSeekClient.summarize(key, result.summary, older, documents, control, true);
        } catch (ApiError error) {
            if (!error.contextLimit || !allowTextOnly || control.isCancelled()) throw error;
            merged = DeepSeekClient.summarize(key, result.summary, older, documents, control, false);
            omitted = true;
        }
        if (!recovering && ContextPlanner.estimatedTokens(Collections.emptyList(), merged) >=
                ContextPlanner.estimatedTokens(older, result.summary)) return;
        result.summary = merged;
        result.throughId = older.get(older.size() - 1).id;
        result.omittedAttachments |= omitted;
    }
}
