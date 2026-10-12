package com.jimeng.dataserver.ai.web;

/** HTML 压成可读文本（零依赖，best-effort）。web_fetch 与 http_request 的 as_text 共用，S3 迁移联网时两边读网页的效果一致。 */
public final class HtmlText {

    private HtmlText() {}

    /** 沿用工具既有的 Content-Type / HTML 开头判定，不扩大解析规则。 */
    public static boolean isHtml(String contentType, String text) {
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("html")) return true;
        String head = text.stripLeading().toLowerCase(java.util.Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html");
    }

    public static String toText(String html) {
        return html
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<head.*?</head>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
