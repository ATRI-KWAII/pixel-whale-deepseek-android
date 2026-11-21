package com.pixelwhale.deepseek;

import java.io.IOException;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

final class ApiError extends IOException {
    final boolean contextLimit;

    private ApiError(String message, boolean contextLimit) {
        super(message);
        this.contextLimit = contextLimit;
    }

    static ApiError contextLimit(String message) {
        return new ApiError(message, true);
    }

    static ApiError fromResponse(int status, String body, boolean chatRequest) {
        if (status == 401) return new ApiError("API Key 无效，请到设置中检查。", false);
        if (status == 402) return new ApiError("DeepSeek 账户余额不足。", false);
        if (status == 429) return new ApiError("请求太频繁，请稍后再试。", false);
        String detail = body;
        String code = "";
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if (error != null) {
                detail = error.optString("message", "");
                code = error.optString("code", "");
            }
        } catch (JSONException ignored) { }
        String signature = (code + " " + detail).toLowerCase(Locale.ROOT);
        if (signature.contains("content exists risk") || signature.contains("content_filter")
                || signature.contains("content_risk")) {
            return new ApiError("DeepSeek 拒绝了本次请求，请调整提问或附件。", false);
        }
        if (chatRequest && (status == 400 || status == 413 || status == 422)) {
            if (signature.contains("context_length_exceeded") || signature.contains("maximum context length") ||
                    signature.matches("(?s).*(context|prompt|input|tokens).{0,80}(too long|exceed|maximum|too many|limit reached).*") ||
                    signature.matches("(?s).*(too many|at most|maximum (number of|of)|no more than).{0,50}(images|documents|files).*") ||
                    signature.matches("(?s).*(images|documents|file references).{0,35}(exceed|too many|per request limit).*") ||
                    status == 413) {
                return contextLimit("DeepSeek 上下文或附件超限。可以减少本轮内容，或从新会话继续。");
            }
        }
        if (status == 408) return new ApiError("DeepSeek 请求超时，请稍后重试。", false);
        if (status == 413) return new ApiError("文件太大，请选择较小的文件。", false);
        if (status == 400 || status == 422) {
            if (signature.contains("reasoning_content"))
                return new ApiError("DeepSeek 无法接续本次联网思考。可关闭联网后重试，或新建会话。", false);
            if (signature.contains("file_id") || signature.contains("file id"))
                return new ApiError("DeepSeek 无法读取引用的文档，请检查文档是否仍在账户中。", false);
            return new ApiError("DeepSeek 未接受本次请求（HTTP " + status + "）。请检查模型设置和附件后重试。", false);
        }
        return new ApiError("DeepSeek 请求失败（HTTP " + status + "）。", false);
    }
}
