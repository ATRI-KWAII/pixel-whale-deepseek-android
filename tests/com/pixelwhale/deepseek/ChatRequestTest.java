package com.pixelwhale.deepseek;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises HTTP and streaming behavior with a fake DeepSeek connection. */
public final class ChatRequestTest {
    private static int status;
    private static String response;
    private static ByteArrayOutputStream sent;
    private static final List<ByteArrayOutputStream> requests = new ArrayList<>();
    private static final Deque<MockResponse> queued = new ArrayDeque<>();

    private static final class MockResponse {
        final int status;
        final String body;

        MockResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected HttpURLConnection openConnection(URL url) {
                check("https://api.deepseek.com/chat/completions".equals(url.toString()), "Unexpected URL");
                ByteArrayOutputStream request = new ByteArrayOutputStream();
                sent = request;
                requests.add(request);
                MockResponse next = queued.isEmpty() ? new MockResponse(status, response) : queued.removeFirst();
                return new HttpURLConnection(url) {
                    @Override public void connect() { }
                    @Override public boolean usingProxy() { return false; }
                    @Override public void disconnect() { }
                    @Override public int getResponseCode() { return next.status; }
                    @Override public OutputStream getOutputStream() { return request; }
                    @Override public InputStream getInputStream() {
                        return new ByteArrayInputStream(next.body.getBytes(StandardCharsets.UTF_8));
                    }
                    @Override public InputStream getErrorStream() { return getInputStream(); }
                };
            }
        } : null);

        List<ConversationStore.Message> chat = Arrays.asList(
                new ConversationStore.Message(1, "user", "Hello"),
                new ConversationStore.Message(2, "assistant", "Hi",
                        null, null, null, "[]", null, null, "Earlier thinking"),
                new ConversationStore.Message(3, "user", "Follow up"));

        status = 400;
        response = "{\"error\":{\"message\":\"Content Exists Risk\"}}";
        expectError(chat, false, "拒绝");
        response = "{\"error\":{\"message\":\"This model's maximum context length is 1048576 tokens.\"}}";
        expectError(chat, true, "超限");
        response = "{\"error\":{\"message\":\"Invalid JSON: content must be a string\"}}";
        expectError(chat, false, "HTTP 400");
        response = "{\"error\":{\"message\":\"The reasoning_content must be passed back\"}}";
        expectError(chat, false, "联网思考");

        status = 200;
        response = frame("{\"reasoning_content\":\"tool step\",\"content\":null}", null, null)
                + frame("{}", "{\"type\":\"step_reasoning_done\",\"step\":0}", null)
                + frame("{\"reasoning_content\":\"final step\"}", null, null)
                + frame("{\"content\":\"Answer\",\"server_tool_calls\":[]}", null, "stop")
                + "data: [DONE]\n";
        DeepSeekClient.Reply reply = reply(chat, true);
        check("Answer".equals(reply.text), "Null chunk should not be displayed");
        check("final step".equals(reply.reasoningContent), "Replay only the final thinking segment");
        JSONArray history = new JSONObject(sent.toString("UTF-8")).getJSONArray("messages");
        JSONObject prior = history.getJSONObject(1);
        check("Earlier thinking".equals(prior.getString("reasoning_content")), "Replay earlier reasoning");
        check(prior.getJSONArray("server_tool_calls").length() == 0, "Replay tool calls unchanged");

        reply(chat, false);
        history = new JSONObject(sent.toString("UTF-8")).getJSONArray("messages");
        check(!history.getJSONObject(1).has("reasoning_content"), "No reasoning required without tools");
        check(history.getJSONObject(1).has("server_tool_calls"), "Keep Flash's earlier URL-reading context");

        response = frame("{\"content\":\"Partial\"}", null, "length") + "data: [DONE]\n";
        check(reply(chat, false).truncated, "Report a truncated response");
        response = frame("{\"content\":\"Partial\"}", null, "content_filter") + "data: [DONE]\n";
        try {
            reply(chat, true);
            throw new AssertionError("Refused output was returned as a normal reply");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("拒绝"), "Preserve refusal details");
        }

        response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"New summary\"}}]}";
        String summary = DeepSeekClient.summarize("test-key", "Old summary", chat.subList(0, 2),
                (message, path, index) -> "unused", new DeepSeekClient.Control(), true);
        check("New summary".equals(summary), "Accept a completed summary");
        String transcript = new JSONObject(sent.toString("UTF-8")).getJSONArray("messages")
                .getJSONObject(1).getString("content");
        check(transcript.contains("Old summary") && transcript.contains("Hello"), "Merge prior summary and old messages");

        ConversationStore.Conversation conversation = new ConversationStore.Conversation(1,
                "Test chat", "flash", "Base prompt", "", 0, 0, "", false, false);
        queued.add(new MockResponse(400, "{\"error\":{\"code\":\"context_length_exceeded\"}}"));
        queued.add(new MockResponse(200, completion("Condensed earlier turns")));
        queued.add(new MockResponse(200, frame("{\"content\":\"Recovered\"}", null, "stop")));
        requests.clear();
        ChatRequest.Outcome recovered = ChatRequest.run("test-key", conversation, chat, false,
                "\nUser preference: explain with examples.", (message, path, index) -> "unused",
                new DeepSeekClient.Control(), text -> { }, text -> { });
        check(recovered.failure == null && recovered.throughId == 2 && "Recovered".equals(recovered.reply.text),
                "A real context limit in a short chat should summarize older turns and retry");
        check(queued.isEmpty() && requests.size() == 3, "Expect one bounded recovery");
        JSONArray retried = new JSONObject(sent.toString("UTF-8")).getJSONArray("messages");
        check(retried.length() == 2 && "Follow up".equals(retried.getJSONObject(1).getString("content")),
                "The latest question must remain verbatim");
        String instruction = retried.getJSONObject(0).getString("content");
        check(instruction.contains("User preference") && instruction.contains("Condensed earlier turns"),
                "Reuse the updated summary and saved memory on retry");

        queued.add(new MockResponse(400, "{\"error\":{\"message\":\"Invalid request format\"}}"));
        requests.clear();
        ChatRequest.Outcome invalid = ChatRequest.run("test-key", conversation, chat, false, "",
                (message, path, index) -> "unused", new DeepSeekClient.Control(), text -> { }, text -> { });
        check(invalid.failure != null && invalid.throughId == 0 && requests.size() == 1,
                "A format error must not trigger any summary or second request");

        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("1:sample", "我希望从实际例子开始学习 Spring Boot");
        MemoryStore.Snapshot snapshot = new MemoryStore.Snapshot(0, Collections.singletonList(
                new MemoryStore.Entry("manual", "用户喜欢简明回答", false)), samples);
        response = completion("{\"memories\":[\"用户偏好从实际例子开始学习 Spring Boot\"]}");
        List<String> organized = DeepSeekClient.organizeMemories("test-key", snapshot, new DeepSeekClient.Control());
        check(organized.size() == 1 && organized.get(0).contains("Spring Boot"), "Parse structured nightly memory");
        JSONObject nightly = new JSONObject(sent.toString("UTF-8"));
        check("json_object".equals(nightly.getJSONObject("response_format").getString("type")),
                "Nightly organization requires JSON mode");
        JSONObject input = new JSONObject(nightly.getJSONArray("messages").getJSONObject(1).getString("content"));
        check(input.getJSONArray("manual_memories").length() == 1
                && input.getJSONArray("recent_user_messages").length() == 1,
                "Nightly organization gets saved notes and a bounded batch of new user text");

        List<MarkdownText.Block> blocks = MarkdownText.blocks("Intro\n\n```java\n    int x = 1;\n```\n\nTail");
        check(blocks.size() == 3 && "java".equals(blocks.get(1).language)
                && "    int x = 1;".equals(blocks.get(1).text), "Preserve code indentation and language");
        System.out.println("ChatRequestTest passed");
    }

    private static DeepSeekClient.Reply reply(List<ConversationStore.Message> chat, boolean web) throws Exception {
        return DeepSeekClient.reply("test-key", "think", web, "", "", chat,
                (message, path, index) -> "unused", new DeepSeekClient.Control(), text -> { }, text -> { });
    }

    private static void expectError(List<ConversationStore.Message> chat, boolean contextLimit,
                                    String detail) throws Exception {
        try {
            reply(chat, false);
            throw new AssertionError("Expected a DeepSeek error");
        } catch (ApiError error) {
            check(error.contextLimit == contextLimit && error.getMessage().contains(detail),
                    "Incorrect error classification: " + error.getMessage());
        }
    }

    private static String completion(String content) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", "stop").put("message", new JSONObject().put("content", content)))).toString();
    }

    private static String frame(String delta, String event, String finish) throws Exception {
        JSONObject choice = new JSONObject().put("delta", new JSONObject(delta));
        if (event != null) choice.put("server_tool_event", new JSONObject(event));
        if (finish != null) choice.put("finish_reason", finish);
        return "data: " + new JSONObject().put("choices", new JSONArray().put(choice)) + "\n";
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
