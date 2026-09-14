package com.jimeng.dataserver.ai.connector.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 个人信息过滤：决定<b>某一列的真实取值能不能离开客户的库</b>。
 *
 * <h3>它为什么必须存在</h3>
 * {@link SemanticDataTier#SAMPLE_VALUES}（第 3 档）允许把客户库里一行行的真实取值带出来，
 * 而低基数列的全量取值、高频取值恰恰是 PII 最集中的地方——姓名、手机号、地址就躺在那里。
 * 同类实现（Tursio）公开的 PII 检测指标是 <b>recall 95% / precision 91%</b>：
 * 做过这件事的人也只能做到「每 20 个 PII 取值仍漏掉 1 个」。
 * 这个数字的用处不是让我们去抄一个 95%，而是说明<b>「不过滤就开第 3 档」是什么量级的事</b>。
 *
 * <h3>★ 这个类刻意偏向召回率，不偏向精确率</h3>
 * 两种错的代价<b>差着性质</b>，不是差着倍数：
 * <ul>
 *   <li><b>误报</b>（把一列正常的维值当成 PII）：少一个值域。模型退回到今天的状态——
 *       它照样能查，只是得自己确认取值怎么写。代价有边界，而且可恢复：人确认一次就行。</li>
 *   <li><b>漏报</b>（把一列手机号当成维值）：客户的手机号先写进<b>我们</b>的库，
 *       再随语义层注入<b>模型提示词</b>，再落进 {@code ai_model_call_content}。
 *       一次漏报产生三份副本，而且发现时已经无法收回。</li>
 * </ul>
 * 所以凡是拿不准的一律拦。下面每一条规则宁可多杀，不可放过——
 * 看到某条规则「管得太宽」时，请先确认放宽它换来的那点覆盖率值不值上面那三份副本。
 *
 * <h3>★ 两层过滤，列名那一层不可省</h3>
 * <ul>
 *   <li><b>列名 / 列注释</b>（{@link #screenColumn}）：一列叫 {@code phone}，
 *       哪怕它<b>此刻每一行都是空的</b>，它依然是手机号列——今天没数据不代表明天没有，
 *       而值域是一次性采集、长期留在我们库里的。只看取值形状的过滤会放过整张空表，
 *       然后在下一次刷新时安静地把真实号码收进来。</li>
 *   <li><b>取值形状</b>（{@link #screenValues}）：列名起得毫无信息量（{@code f1}、{@code col3}）
 *       时唯一的判据。</li>
 * </ul>
 *
 * <h3>★ 命中就整列丢弃，不做脱敏替换</h3>
 * 直觉上应该把手机号打码后留下来。<b>不要这么做</b>：值域的<b>全部用途</b>是让模型照着写
 * {@code WHERE region = '华东'}。一个 {@code 138****0000} 既不能用来写条件，
 * 又会让模型以为自己见过这一列的取值——比没有更糟。
 * 同理，命中一个取值就丢<b>整列</b>而不是只丢那一个：留下的那个「缺了几项的集合」
 * 会被当成全集用，正是本设计反复强调要避免的那类静默残缺。
 * {@link #maskForLog(String)} 只给日志用，产出永远不含它。
 *
 * <h3>★ 一条刻意不做的规则：不按取值形状认中文人名</h3>
 * 「2～4 个汉字」这条规则对 {@code 张伟} 和 {@code 华东} 是<b>同一个判断</b>——
 * 而 {@code 华东} 正是整个值域剖析要解决的那个案例（用户问「华东区」、库里存「华东」）。
 * 一条精确率接近 0、又恰好杀掉核心用例的规则，不是保守，是坏规则。
 * 人名的信号在<b>列名</b>里（{@link #PERSON_QUALIFIERS}），不在取值形状里；
 * 何况人名列几乎必然是高基数列，值域剖析的基数闸本来就会先把它挡掉，
 * 列名这一层是给「20 个人的小员工表」准备的第二道。
 */
@Slf4j
@Component
public class PiiFilter {

    /**
     * 一次判定。<b>内部形状，不会被 JSON 序列化</b>（本仓库 jackson 2.11.1 序列化不了 record），
     * 调用方拿到的只是 {@link #reason()} 这句话，落库的也只是那句话。
     */
    public record PiiVerdict(boolean sensitive, String reason) {

        private static final PiiVerdict CLEAN = new PiiVerdict(false, null);

        public static PiiVerdict clean() {
            return CLEAN;
        }

        public static PiiVerdict flagged(String reason) {
            return new PiiVerdict(true, reason);
        }
    }

    // ================================================================ 列名判据

    /**
     * 单个词命中即拦的列名词元。<b>按「词元」精确匹配，不按子串</b>——
     * 子串匹配会让 {@code ship_type} 因为含 {@code ip}、{@code package_type} 因为含 {@code age}
     * 被误杀。那种误杀不是「偏保守」，它是随机的：命中与否取决于业务词里碰巧藏了哪三个字母，
     * 于是同一个客户的两张表表现不一致，没人能解释为什么。
     */
    private static final Set<String> SENSITIVE_TOKENS = Set.of(
            // 联系方式
            "phone", "mobile", "cellphone", "tel", "telephone", "fax", "email", "mail", "mailbox",
            "wechat", "weixin", "qq", "whatsapp", "telegram", "contact",
            // 证件
            "passport", "ssn", "idcard", "idno", "identity",
            // 金融
            "iban", "cardno", "cardnum", "payee", "payer",
            // 住址
            "address", "addr", "postcode", "zipcode", "street",
            // 人（整词本身就指向人）
            "username", "realname", "fullname", "firstname", "lastname", "nickname", "surname",
            "linkman", "consignee", "applicant",
            // 账号与秘密
            "password", "passwd", "pwd", "secret", "token", "apikey", "privatekey", "salt",
            "openid", "unionid", "imei", "idfa", "oaid",
            "ip",
            // 其它个人属性
            "birthday", "birthdate", "dob", "age", "gender", "sex", "nation", "ethnic",
            "marital", "salary", "income", "bonus", "avatar", "fingerprint");

    /**
     * 相邻两个词元命中即拦。拆成词对而不是写死 {@code id_card_no} 这样的整名，
     * 是因为同一个概念在客户库里有十几种写法（{@code idCard} / {@code id_card} / {@code ID_CARD_NO}），
     * 枚举整名必然漏，枚举词对不会。
     */
    private static final Set<String> SENSITIVE_PAIRS = Set.of(
            "id|card", "id|no", "card|no", "card|num", "card|number",
            "bank|card", "bank|account", "bank|no", "account|no", "credit|card", "debit|card",
            "tax|no", "tax|id", "social|security", "driver|license", "license|no", "license|plate",
            "plate|no", "device|id", "birth|date", "date|of", "home|address", "mail|address",
            "post|code", "zip|code", "phone|no", "mobile|no", "contact|way", "contact|info",
            // 秘密类：单看 key / secret 太容易误伤业务列（business_key、secret_level），成对才判
            "api|key", "app|key", "app|secret", "private|key", "public|key", "secret|key",
            "access|key", "session|key", "id|token");

    /**
     * ★ {@code name} 这个词元单独一条规则，因为它同时是<b>最危险</b>和<b>最有价值</b>的那个词。
     *
     * <p>{@code region_name} / {@code status_name} / {@code type_name} 是维值列——
     * 它们正是值域剖析的主要目标，一刀切拦掉等于把这个功能关了。
     * {@code user_name} / {@code customer_name} 是人（或公司）的名字，采集它等于把客户的
     * 通讯录搬进我们的库；{@link SemanticDataTier#SAMPLE_VALUES} 的注释把「客户名称列的 top-k」
     * 直接点名为这一档最露骨的后果。
     *
     * <p>判据是<b>限定词</b>：{@code name} 前面那个词认得出来是人就拦，认得出来不是人就放。
     * 没有限定词（列就叫 {@code name}）也拦——<b>认不出来就当最严的处理</b>，
     * 与 {@link SemanticDataTier#parse(String)} 对无法识别的档位值的兜底方向一致。
     * 代价说在明处：一张 {@code t_region} 表里那列如果就叫 {@code name}，它的值域会被丢掉。
     * 补救办法是人确认一次，不是把这条放宽。
     */
    private static final Set<String> PERSON_QUALIFIERS = Set.of(
            "user", "users", "real", "full", "first", "last", "nick", "sur", "given", "middle",
            "contact", "customer", "cust", "client", "employee", "emp", "staff", "person", "people",
            "member", "patient", "student", "teacher", "doctor", "driver", "author", "creator",
            "operator", "manager", "owner", "buyer", "seller", "supplier", "vendor", "consignee",
            "receiver", "recipient", "sender", "linkman", "guest", "account", "login", "applicant",
            "approver", "handler", "agent", "partner", "legal", "holder");

    /** 中文列名 / 列注释：中文没有词边界问题，直接子串匹配即可，不需要词元化。 */
    private static final List<String> SENSITIVE_CJK = List.of(
            "姓名", "名字", "真实姓名", "用户名", "昵称", "联系人", "收件人", "发件人", "经办人",
            "负责人", "申请人", "开户名", "客户名", "法人",
            "手机", "电话", "座机", "传真", "邮箱", "电邮", "微信", "钉钉",
            "身份证", "证件号", "护照", "军官证", "港澳", "台胞",
            "银行卡", "卡号", "账号", "帐号", "开户行", "银行账", "支付宝",
            "地址", "住址", "邮编", "门牌", "车牌",
            "密码", "口令", "密钥", "私钥", "签名", "令牌",
            "生日", "出生", "年龄", "性别", "民族", "婚姻", "学历", "籍贯",
            "薪资", "工资", "收入", "奖金", "社保", "公积金", "指纹", "人脸", "头像");

    // ================================================================ 取值形状判据

    /** 宽松到过分是有意的：{@code a@b.c} 就算。收窄它只会漏掉花式写法的邮箱。 */
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}");

    /** 身份证：18 位（末位可能是 X）或 15 位老号。 */
    private static final Pattern CN_ID = Pattern.compile("^\\d{17}[\\dXx]$|^\\d{15}$");

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** 手机号 / 银行卡 / QQ / 固话的共同形状：剥掉分隔符之后是一串够长的纯数字。 */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-()+]");

    /**
     * 长数字串的阈值。7 位是国内固话的最短形态；再往下就会撞上业务编码
     *（6 位邮编、4 位年份、3 位状态码），那些不是 PII。
     *
     * <p>它确实会误伤「8 位的日期整数」「7 位以上的商品编码」这类列——按本类的取舍，
     * 这笔账是划算的：丢一个值域，换不漏一列手机号。
     */
    private static final int LONG_DIGITS_MIN = 7;

    /**
     * 街道级地址词。<b>刻意不含 省 / 市 / 区 / 县</b>：那四个字是最典型的<b>维值</b>
     *（「华东」「浙江省」「海淀区」），把它们当地址拦掉，就把这个功能最主要的用例杀了。
     * 真正指向某一个具体住处的是门牌层级的词。
     */
    private static final List<String> STREET_MARKERS = List.of(
            "路", "号", "街", "巷", "弄", "室", "栋", "幢", "单元", "小区", "村", "组", "座", "层");

    /** 地址判定的最短长度。「北京市」「浙江省」这类行政区划不会到这个长度，具体地址一定超过。 */
    private static final int ADDRESS_MIN_LEN = 8;

    // ================================================================ 对外

    /**
     * 列名 / 列注释这一层。<b>在去客户库查任何取值之前调用</b>——
     * 一列手机号不该被查一遍再丢掉，它压根不该被查。
     *
     * @param objectName 表名，只用于日志定位
     * @param columnName 列名
     * @param comment    客户写在库里的列注释，可能为空
     */
    public PiiVerdict screenColumn(String objectName, String columnName, String comment) {
        if (columnName == null || columnName.isBlank()) {
            // 没有名字可判 = 判不了。判不了就当敏感，与本类整体的兜底方向一致。
            return PiiVerdict.flagged("列名为空，无法判断是否为个人信息列");
        }
        PiiVerdict byName = screenText(columnName, "列名");
        if (byName.sensitive()) {
            log.debug("值域剖析跳过疑似个人信息列 object={} column={} reason={}",
                    objectName, columnName, byName.reason());
            return byName;
        }
        if (comment != null && !comment.isBlank()) {
            PiiVerdict byComment = screenText(comment, "列注释");
            if (byComment.sensitive()) {
                return byComment;
            }
        }
        return PiiVerdict.clean();
    }

    /**
     * 取值形状这一层。<b>命中任意一个取值就否决整批</b>，理由见类注释「命中就整列丢弃」。
     *
     * <p>返回的 {@link PiiVerdict#reason()} 里<b>不会出现任何原始取值</b>：
     * 这句话会被写进我们的库、被注入模型上下文、被打进日志，
     * 把一个被判定为 PII 的取值抄进「我们拦下了 PII」这句话里，是本仓库最容易犯的那种自相矛盾。
     */
    public PiiVerdict screenValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return PiiVerdict.clean();
        }
        for (String v : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            String s = v.trim();
            if (EMAIL.matcher(s).find()) {
                return PiiVerdict.flagged("取值中出现疑似邮箱地址");
            }
            if (CN_ID.matcher(s).matches()) {
                return PiiVerdict.flagged("取值中出现疑似身份证号");
            }
            if (IPV4.matcher(s).matches()) {
                return PiiVerdict.flagged("取值中出现疑似 IP 地址");
            }
            if (isLongDigitString(s)) {
                return PiiVerdict.flagged("取值中出现长数字串（疑似手机号 / 银行卡号 / 证件号）");
            }
            if (looksLikeStreetAddress(s)) {
                return PiiVerdict.flagged("取值中出现疑似详细地址");
            }
        }
        return PiiVerdict.clean();
    }

    /**
     * 两层合起来问一次，值域剖析的实际入口。
     *
     * <p>列名那一层先判，是因为它<b>不需要取值</b>：空表上的手机号列同样被拦下，
     * 而只看取值的过滤会在这里放行，等下次刷新时真实号码就进来了。
     */
    public PiiVerdict screen(String objectName, String columnName, String comment, List<String> values) {
        PiiVerdict byColumn = screenColumn(objectName, columnName, comment);
        if (byColumn.sensitive()) {
            return byColumn;
        }
        return screenValues(values);
    }

    /**
     * 日志里要提到某个客户取值时用。<b>产出（写进我们库、进模型提示词的东西）永远不用它</b>——
     * 打码后的值对模型没有用处，只会让它以为自己见过这一列的取值。
     */
    public static String maskForLog(String value) {
        if (value == null) {
            return "null";
        }
        int len = value.length();
        if (len == 0) {
            return "(空串)";
        }
        String head = value.substring(0, 1);
        return head + "***(len=" + len + ")";
    }

    // ================================================================ 内部

    /** 一段文本（列名或注释）过一遍所有列名判据。 */
    private PiiVerdict screenText(String text, String where) {
        for (String cjk : SENSITIVE_CJK) {
            if (text.contains(cjk)) {
                return PiiVerdict.flagged(where + "含「" + cjk + "」，按个人信息列处理");
            }
        }
        List<String> tokens = tokenize(text);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (SENSITIVE_TOKENS.contains(t)) {
                return PiiVerdict.flagged(where + "含「" + t + "」，按个人信息列处理");
            }
            if (i + 1 < tokens.size() && SENSITIVE_PAIRS.contains(t + "|" + tokens.get(i + 1))) {
                return PiiVerdict.flagged(where + "含「" + t + "_" + tokens.get(i + 1) + "」，按个人信息列处理");
            }
            if ("name".equals(t)) {
                PiiVerdict v = judgeName(tokens, i, where);
                if (v.sensitive()) {
                    return v;
                }
            }
        }
        return PiiVerdict.clean();
    }

    /** {@code name} 的限定词判定，规则与代价见 {@link #PERSON_QUALIFIERS}。 */
    private PiiVerdict judgeName(List<String> tokens, int idx, String where) {
        if (idx == 0 && tokens.size() == 1) {
            return PiiVerdict.flagged(where + "就叫「name」，限定词缺失、无法判断是不是人名，按个人信息列处理");
        }
        if (idx > 0 && PERSON_QUALIFIERS.contains(tokens.get(idx - 1))) {
            return PiiVerdict.flagged(where + "含「" + tokens.get(idx - 1) + "_name」，按个人信息列处理");
        }
        return PiiVerdict.clean();
    }

    /**
     * 把列名切成词元：驼峰拆开、分隔符拆开、全部转小写。
     *
     * <p>{@code userPhone} / {@code user_phone} / {@code USER_PHONE} 必须切出同一组词元——
     * 客户库里这三种写法都有，判据对它们表现不一致就等于随机漏报。
     */
    static List<String> tokenize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() * 2);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(raw.charAt(i - 1))) {
                sb.append('_');
            }
            sb.append(c);
        }
        String[] parts = sb.toString().toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        return java.util.Arrays.stream(parts).filter(s -> !s.isEmpty()).toList();
    }

    /** 剥掉分隔符之后是不是一串够长的纯数字。 */
    private static boolean isLongDigitString(String s) {
        String digits = SEPARATORS.matcher(s).replaceAll("");
        if (digits.length() < LONG_DIGITS_MIN) {
            return false;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeStreetAddress(String s) {
        if (s.length() < ADDRESS_MIN_LEN) {
            return false;
        }
        for (String marker : STREET_MARKERS) {
            if (s.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
