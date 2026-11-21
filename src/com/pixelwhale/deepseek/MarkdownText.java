package com.pixelwhale.deepseek;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small on-device formatter for common Markdown in API replies. */
final class MarkdownText {
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final int CODE_TINT = 0xfff0e9de;

    static final class Block {
        final String text;
        final String language;

        Block(String text, String language) {
            this.text = text;
            this.language = language;
        }
    }

    static List<Block> blocks(String source) {
        List<Block> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        String fence = null;
        String language = null;
        for (String line : source.replace("\r\n", "\n").split("\n", -1)) {
            Matcher match = FENCE.matcher(line);
            if (match.matches()) {
                String marker = match.group(1);
                if (fence == null) {
                    if (text.length() > 0) result.add(new Block(text.toString().trim(), null));
                    text.setLength(0);
                    fence = marker;
                    language = match.group(2).trim();
                    continue;
                }
                if (marker.charAt(0) == fence.charAt(0) && marker.length() >= fence.length()
                        && match.group(2).trim().isEmpty()) {
                    result.add(new Block(codeText(text), language));
                    text.setLength(0);
                    fence = null;
                    language = null;
                    continue;
                }
            }
            text.append(line).append('\n');
        }
        if (fence != null) result.add(new Block(codeText(text), language));
        else if (text.length() > 0) result.add(new Block(text.toString().trim(), null));
        return result;
    }

    private static String codeText(StringBuilder text) {
        return text.length() == 0 ? "" : text.substring(0, text.length() - 1);
    }

    static SpannableStringBuilder render(String source) {
        SpannableStringBuilder result = new SpannableStringBuilder();
        String[] lines = source.split("\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            int heading = 0;
            while (heading < line.length() && heading < 6 && line.charAt(heading) == '#')
                heading++;
            if (heading > 0 && heading < line.length() && line.charAt(heading) == ' ')
                line = line.substring(heading + 1);
            else heading = 0;
            if (line.startsWith("- ") || line.startsWith("* "))
                line = "• " + line.substring(2);
            int start = result.length();
            appendInline(result, line);
            int end = result.length();
            if (heading > 0 && end > start) {
                result.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new RelativeSizeSpan(heading == 1 ? 1.24f : 1.1f), start, end,
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
                    result.setSpan(new BackgroundColorSpan(CODE_TINT),
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
