package com.jimeng.dataserver.ai.web;

import java.io.InterruptedIOException;

/** HTML 压成可读文本（零依赖，best-effort）。按原有删除顺序线性扫描，不从每个畸形开头重复搜索整个后缀。 */
public final class HtmlText {

    private static final int CHECK_CHARS = 4096;

    private HtmlText() {}

    /** 沿用工具既有的 Content-Type / HTML 开头判定，不扩大解析规则。 */
    public static boolean isHtml(String contentType, String text) {
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("html")) return true;
        String head = text.stripLeading().toLowerCase(java.util.Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html");
    }

    public static String toText(String html) {
        try {
            return toText(html, Long.MAX_VALUE);
        } catch (InterruptedIOException e) {
            throw new IllegalStateException(e); // 未指定截止时间的调用不会走到这里。
        }
    }

    /** 网关传入同一次请求的 nanoTime 截止时间；超时在调用线程退出，不留下后台转换任务。 */
    public static String toText(String html, long deadlineNanos) throws InterruptedIOException {
        checkBudget(deadlineNanos);
        String text = removeBlocks(html, "<script", "</script>", true, deadlineNanos);
        text = removeBlocks(text, "<style", "</style>", true, deadlineNanos);
        text = removeBlocks(text, "<head", "</head>", true, deadlineNanos);
        text = removeBlocks(text, "<!--", "-->", false, deadlineNanos);
        text = removeTags(text, deadlineNanos);
        text = replace(text, "&nbsp;", " ", deadlineNanos);
        text = replace(text, "&amp;", "&", deadlineNanos);
        text = replace(text, "&lt;", "<", deadlineNanos);
        text = replace(text, "&gt;", ">", deadlineNanos);
        return collapseWhitespace(text, deadlineNanos);
    }

    private static String removeBlocks(String text, String open, String close, boolean ignoreCase,
                                       long deadline) throws InterruptedIOException {
        StringBuilder out = null;
        int cursor = 0;
        while (true) {
            int start = find(text, open, cursor, ignoreCase, deadline);
            if (start < 0) break;
            int end = find(text, close, start + open.length(), ignoreCase, deadline);
            // 第一个开头都找不到结束符，后面的开头也找不到，保留整个未匹配后缀。
            if (end < 0) break;
            if (out == null) out = new StringBuilder(text.length());
            append(out, text, cursor, start, deadline);
            out.append(' ');
            cursor = end + close.length();
        }
        return finish(out, text, cursor, deadline);
    }

    private static String removeTags(String text, long deadline) throws InterruptedIOException {
        StringBuilder out = null;
        int cursor = 0, search = 0;
        while (true) {
            int start = find(text, "<", search, false, deadline);
            if (start < 0 || start + 1 == text.length()) break;
            // 旧的 <[^>]+> 不匹配 <>，但可以匹配 <<>。
            if (text.charAt(start + 1) == '>') {
                search = start + 1;
                continue;
            }
            int end = find(text, ">", start + 2, false, deadline);
            if (end < 0) break;
            if (out == null) out = new StringBuilder(text.length());
            append(out, text, cursor, start, deadline);
            out.append(' ');
            cursor = search = end + 1;
        }
        return finish(out, text, cursor, deadline);
    }

    private static String replace(String text, String token, String value, long deadline) throws InterruptedIOException {
        StringBuilder out = null;
        int cursor = 0, start;
        while ((start = find(text, token, cursor, false, deadline)) >= 0) {
            if (out == null) out = new StringBuilder(text.length());
            append(out, text, cursor, start, deadline);
            out.append(value);
            cursor = start + token.length();
        }
        return finish(out, text, cursor, deadline);
    }

    private static int find(String text, String token, int from, boolean ignoreCase,
                            long deadline) throws InterruptedIOException {
        checkBudget(deadline);
        for (int i = from, last = text.length() - token.length(); i <= last; i++) {
            if ((i & (CHECK_CHARS - 1)) == 0) checkBudget(deadline);
            int j = 0;
            while (j < token.length()) {
                char c = text.charAt(i + j);
                if (ignoreCase && c >= 'A' && c <= 'Z') c += 'a' - 'A';
                if (c != token.charAt(j)) break;
                j++;
            }
            if (j == token.length()) return i;
        }
        checkBudget(deadline);
        return -1;
    }

    private static void append(StringBuilder out, String text, int from, int to,
                               long deadline) throws InterruptedIOException {
        for (int cursor = from; cursor < to; cursor += CHECK_CHARS) {
            checkBudget(deadline);
            out.append(text, cursor, Math.min(to, cursor + CHECK_CHARS));
        }
    }

    private static String finish(StringBuilder out, String text, int cursor,
                                 long deadline) throws InterruptedIOException {
        if (out == null) return text;
        append(out, text, cursor, text.length(), deadline);
        String result = out.toString();
        checkBudget(deadline);
        return result;
    }

    private static String collapseWhitespace(String text, long deadline) throws InterruptedIOException {
        checkBudget(deadline);
        StringBuilder out = new StringBuilder(text.length());
        boolean previousSpace = false;
        for (int i = 0; i < text.length(); i++) {
            if ((i & (CHECK_CHARS - 1)) == 0) checkBudget(deadline);
            char c = text.charAt(i);
            boolean space = c == ' ' || c == '\t' || c == '\n' || c == '\u000b' || c == '\f' || c == '\r';
            if (!space || !previousSpace) out.append(space ? ' ' : c);
            previousSpace = space;
        }
        // String.trim() 原来只去首尾 <= U+0020 的字符，不去 NBSP 等 Unicode 空白。
        int start = 0, end = out.length();
        while (start < end && out.charAt(start) <= ' ') {
            if ((start & (CHECK_CHARS - 1)) == 0) checkBudget(deadline);
            start++;
        }
        while (end > start && out.charAt(end - 1) <= ' ') {
            if ((end & (CHECK_CHARS - 1)) == 0) checkBudget(deadline);
            end--;
        }
        String result = out.substring(start, end);
        checkBudget(deadline);
        return result;
    }

    private static void checkBudget(long deadline) throws InterruptedIOException {
        if (deadline != Long.MAX_VALUE && deadline - System.nanoTime() <= 0) {
            throw new InterruptedIOException("页面文本转换超过请求时限");
        }
    }
}
