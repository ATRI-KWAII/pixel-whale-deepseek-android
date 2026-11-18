package com.pixelwhale.deepseek;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/** Small on-device formatter for common Markdown in API replies. */
final class MarkdownText {
    static SpannableStringBuilder render(String source) {
        SpannableStringBuilder result = new SpannableStringBuilder();
        String[] lines = source.split("\n", -1);
        boolean code = false;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            if (line.trim().startsWith("```")) {
                code = !code;
                continue;
            }
            int heading = 0;
            if (!code) {
                while (heading < line.length() && heading < 3 && line.charAt(heading) == '#')
                    heading++;
                if (heading > 0 && heading < line.length() && line.charAt(heading) == ' ')
                    line = line.substring(heading + 1);
                else heading = 0;
                if (line.startsWith("- ") || line.startsWith("* "))
                    line = "• " + line.substring(2);
            }
            int start = result.length();
            if (code) result.append(line);
            else appendInline(result, line);
            int end = result.length();
            if (heading > 0 && end > start) {
                result.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new RelativeSizeSpan(heading == 1 ? 1.25f : 1.1f), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (code && end > start) {
                result.setSpan(new TypefaceSpan("monospace"), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new BackgroundColorSpan(Color.rgb(233, 243, 245)), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (lineIndex < lines.length - 1) result.append('\n');
        }
        return result;
    }

    private static void appendInline(SpannableStringBuilder result, String line) {
        int i = 0;
        while (i < line.length()) {
            if (line.startsWith("**", i)) {
                int end = line.indexOf("**", i + 2);
                if (end > i + 2) {
                    int start = result.length();
                    result.append(line.substring(i + 2, end));
                    result.setSpan(new StyleSpan(Typeface.BOLD), start, result.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (line.charAt(i) == '`') {
                int end = line.indexOf('`', i + 1);
                if (end > i + 1) {
                    int start = result.length();
                    result.append(line.substring(i + 1, end));
                    result.setSpan(new TypefaceSpan("monospace"), start, result.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    result.setSpan(new BackgroundColorSpan(Color.rgb(233, 243, 245)),
                            start, result.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            result.append(line.charAt(i));
            i++;
        }
    }
}
