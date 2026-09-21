package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.persistence.entity.ConnectorSemantic;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 口径的<b>确定性命中</b>：用户这一轮说的话里出现了哪些已确认的业务词条，把那几条口径的定义
 * 原样摆到模型面前。<b>纯函数，不连库、不叫模型、不碰客户系统。</b>
 *
 * <h3>一、为什么这件事不能交给模型</h3>
 * 「销售额扣除退款」这句话一旦被人确认过、写进 METRIC 行，它就是这条连接上的唯一标准。
 * 但 P1/P2 把它放进 {@code conn_catalog} 的 glossary 之后，生效与否<b>取决于模型有没有注意到那一条</b>：
 * glossary 有几十条、目录里还有上百张表，模型漏读一条不会报错，它会用自己的常识算一个数，
 * 那个数看起来完全正常。设计里这条纪律是硬的：<b>「口径这类必须 100% 正确的东西，用确定性规则做，
 * 不要交给模型理解。」</b>所以命中判断必须是代码做的、可枚举的、可测的。
 *
 * <h3>★ 二、为什么<b>不</b>改写用户的原话（这是已经拍板的决定，不要"修"回去）</h3>
 * 参考实现（Quick BI 的「强制改写」）是<b>直接替换 query 字符串</b>的：命中业务定义/同义词就把
 * 用户那句话改掉再送进模型。我们<b>刻意不这么做</b>，理由不是风格问题：
 * <ul>
 *   <li>改写之后，<b>用户说的话</b>和<b>模型看到的话</b>不再是同一句。答案错了的时候，
 *       对话记录里已经没有任何材料能复原"模型当时到底读到了什么"——排查从有据可查变成猜。</li>
 *   <li>本仓库的审计、trace_id 回溯、口径变更留痕全都建立在"对话是可复核的"这个前提上。
 *       替换用户输入把这个前提抽掉了，而且抽得很安静。</li>
 *   <li>确定性并不来自"替换"这个动作，而来自"命中判断是代码做的"。把命中结果写成一段
 *       <b>显式、有边界、带依据</b>的附加说明，命中率和强制力完全一样，
 *       代价却只是多几十个 token，而不是一段无法复核的对话。</li>
 * </ul>
 * 所以本类的产物是一段<b>附加上下文</b>（{@link MetricContext#getBlock()}），
 * 调用方把它拼在用户原话<b>旁边</b>，永远不要拿它去替换用户原话。
 *
 * <h3>三、召回是要控制的，不是越多越好</h3>
 * 火山方舟知识库那套「命中条件 + 召回上限」的官方建议说得很直白：
 * <b>「『每次都提供给大模型作参考』不是最优配置」</b>、<b>「应添加多条精确规则的知识，
 * 而非一条模糊规则的知识」</b>，并给出召回上限 2–5 的经验值。把这条连接上所有口径每轮全灌进去，
 * 正好是它说的那种反面配置：真正相关的那一条被十几条无关定义稀释掉。
 * 所以这里有 {@link #HARD_MAX_TERMS} 这个<b>写死的天花板</b>——配置只能在它之下调。
 *
 * <h3>四、只认人确认过的口径</h3>
 * 只有 {@code source=HUMAN} 且 {@code status=CONFIRMED} 的 METRIC 行有资格被强制摆出来
 * （见 {@link #isEligible}）。机器推断出来的口径<b>根本不该存在</b>——口径的答案不在数据库里，
 * 任何看起来推出来的都是编的；把一条编出来的口径按"唯一标准"的措辞塞给模型，
 * 比不给更糟。{@code STALE} 同理：它的 SQL 片段引用的列已经对不上了，而错的口径不会报错。
 *
 * <p>不看 {@code verified}：采样验证是给<b>结构推断</b>（字段含义 / 表关系）用的，
 * 口径压根不在数据库里，{@code V_NONE} 就是它的正常值，拿它当门槛会把所有口径挡光。
 *
 * <h3>五、每一条都必须带依据</h3>
 * 任何能对话的人都能覆盖口径且<b>不做权限区分</b>，而覆盖是无声的：被改之后，下一轮问到这个词
 * 只会得到一个按新口径算出来的、看着很正常的数字。
 * 「9月13日由张三确认」这半句话就是口径被改坏之后，业务方在日常使用中<b>看得见</b>它、
 * 并说一句「不对，那是老口径」的地方。
 *
 * <p>纠正路径今天是有的：管理台的语义层抽屉可以<b>删掉</b>一行
 * （{@code DELETE /admin/connectors/{id}/semantic/{rowId}}）。但它<b>只对企业超管开放</b>——
 * 而发现口径记错的通常是业务方，他点不到那个按钮。所以这半句依然是整条链路的<b>起点</b>：
 * 先有人看见，才谈得上有人去找超管删。省掉它，一个错口径可以错上几个月都没人知道。
 *
 * <p>★ {@link #metricBasis} 生成的那几句文案<b>一个字都不要改</b>（{@code MetricRewriterTest}
 * 钉了三条字面量）。这一节解释的是「为什么这半句不能省」，不是文案本身。
 *
 * <h3>六、漏命中和误命中的代价<b>不对称</b></h3>
 * 漏命中的代价是<b>退回到本阶段之前</b>——glossary 本来就还在 {@code conn_catalog} 里，
 * 模型仍然可能读到它。误命中的代价才是新增的：一条不该被强制执行的口径被写成了"唯一标准"，
 * 模型据此算出一个看着正常的错数字。所以本类每一处取舍<b>一律往"宁可漏"的方向倒</b>：
 * 引号区间宁可多圈一点、边界规则宁可严一点、上限宁可小一点。
 *
 * <h3>七、这个类没有任何依赖</h3>
 * 和 {@link ConnectorSemanticService} 同一条约束：它会被注入到工具执行路径上，
 * 那条路径禁止引入任何能回到 {@code ProviderRegistry} / {@code ChatClient} 的 bean
 * （闭合依赖环会让启动直接失败，仓库里已经被咬过两次）。本类连 mapper 都不注入：
 * 行由调用方查好传进来，匹配逻辑是纯函数，因此可以离线单测。
 *
 * @see ConnectorSemanticService#defineMetric 口径是怎么被沉淀成 METRIC 行的
 */
@Slf4j
@Service
public class MetricRewriter {

    /**
     * 每轮最多摆出几条口径的<b>硬上限</b>，配置只能往小调、不能突破。
     *
     * <p>写死 5 是因为配置是会被写错的：{@code metric-context-max-terms=50} 不会报错，
     * 它只会让这个类退化成"每轮把所有口径全灌进去"，也就是被引用的官方建议明确说的那种反面配置，
     * 而且没有任何信号。上限属于这条纪律本身，不属于可调项。
     */
    static final int HARD_MAX_TERMS = 5;

    /** 下限 1：配成 0 或负数等于把这个功能静默关掉，那应该是删掉调用点，不是填个 0。 */
    static final int HARD_MIN_TERMS = 1;

    /** 单条口径说明压平后允许占的位置由总预算控制；这里只兜住"一条都放不下"的退化情形。 */
    static final int MIN_MAX_CHARS = 200;

    /** 口径块的标题。与结尾的说明一样<b>不计入字符预算</b>——砍掉它们正好砍掉了截断的唯一解药。 */
    private static final String BLOCK_HEAD = "【已确认的业务口径】";

    private static final String EMPTY = "";

    /**
     * 只有这三种是<b>字面量</b>引号。
     *
     * <p>中文的「」『』“”‘’ 一律<b>不算</b>：中文里用它们恰恰是在<b>提到这个词</b>
     * （「销售额」怎么算？），那正是最该命中的场景。把它们当引号排除掉，会把这个功能最主要的
     * 用法整个废掉。反过来，{@code '} {@code "} {@code `} 圈起来的通常是一个<b>取值</b>或一段代码
     * （{@code where category = '销售额'}、粘贴的报错信息），那里的词是数据不是概念。
     */
    private static final char[] LITERAL_QUOTES = {'\'', '"', '`'};

    /**
     * 每轮最多摆出几条。默认 3，取的是官方建议 2–5 的中位。
     *
     * <p>键名刻意叫 {@code metric-context-*} 而不是 {@code rewrite-*}：这段东西是<b>附加上下文</b>，
     * 不是对用户原话的改写，见类注释第二节。名字写成"改写"，迟早有人照着名字去实现改写。
     */
    @Value("${connector.semantic.metric-context-max-terms:3}")
    private int configuredMaxTerms;

    /**
     * 口径条目总共允许占多少字符。
     *
     * <p>gloss 是用户在对话里自己敲进去的自由文本，长度不设防。上限 5 条 × 一条几万字，
     * 会把真正的对话上下文整个挤掉，而且每轮都挤。
     */
    @Value("${connector.semantic.metric-context-max-chars:1200}")
    private int configuredMaxChars;

    private int maxTerms = 3;
    private int maxChars = 1200;

    /**
     * 配置只在启动时校验一次并夹紧。
     *
     * <p>夹紧而不是启动失败：这是一个叠加增强，用一个配置笔误去否决整个服务是错的
     * （与 {@link ConnectorSemanticService} 「读失败一律返回空、不抛」同一条纪律）。
     * 但夹紧<b>必须留下日志</b>——静默地把 50 改成 5，和静默地把 5 放大成 50 一样是配置失效。
     */
    @PostConstruct
    void resolveLimits() {
        maxTerms = clampTerms(configuredMaxTerms);
        if (maxTerms != configuredMaxTerms) {
            log.warn("connector.semantic.metric-context-max-terms={} 超出允许区间 [{},{}]，本次按 {} 生效",
                    configuredMaxTerms, HARD_MIN_TERMS, HARD_MAX_TERMS, maxTerms);
        }
        maxChars = Math.max(MIN_MAX_CHARS, configuredMaxChars);
        if (maxChars != configuredMaxChars) {
            log.warn("connector.semantic.metric-context-max-chars={} 太小，本次按 {} 生效",
                    configuredMaxChars, maxChars);
        }
    }

    // ================================================================ 入口

    /**
     * 用配置好的上限做一次命中。
     *
     * <p>★ {@code userText} 只传<b>用户这一轮说的话</b>，不要把整段对话历史拼进来。
     * 传历史的后果很具体：三轮前提过一次「销售额」，之后每一轮都会把它重新注入一遍，
     * 于是这个类就变成了"每次都提供给大模型作参考"——正是被引用的官方建议说不是最优配置的那种。
     *
     * @param metrics 候选行，由调用方从库里取（本类不碰库）。<b>必须是同一条连接的</b>，
     *                理由见 {@link #detectWithLimits}。
     */
    public MetricContext detect(String userText, List<ConnectorSemantic> metrics) {
        return detectWithLimits(userText, metrics, maxTerms, maxChars);
    }

    /**
     * 纯函数版本：不依赖任何字段，上限由调用方给。测试与调参走这里。
     *
     * <h4>为什么假定 {@code metrics} 来自同一条连接</h4>
     * 产出的块里<b>没有"这是哪条连接的口径"这一栏</b>。把两条连接的口径合并传进来，
     * 同一个词就会出现两条互相矛盾的定义，而模型无从区分该用哪条——它会挑一条，
     * 你不知道它挑了哪条。所以这里按"同名只留一条、取 {@code answeredAt} 更晚的那条"兜底，
     * 但真正该修的是调用点：<b>一条连接一次调用</b>。
     *
     * @param maxTerms 会被夹到 [{@link #HARD_MIN_TERMS}, {@link #HARD_MAX_TERMS}]
     */
    public static MetricContext detectWithLimits(String userText, List<ConnectorSemantic> metrics,
                                                 int maxTerms, int maxChars) {
        int cap = clampTerms(maxTerms);
        int budget = Math.max(MIN_MAX_CHARS, maxChars);
        if (userText == null || userText.isBlank() || metrics == null || metrics.isEmpty()) {
            return empty(cap);
        }

        // 全程在小写串上做：引号区间和词条都在同一个串里定位，下标天然对齐。
        // （toLowerCase 在个别字符上会改变长度，拿原串算区间、拿小写串找词就会错位。）
        String hay = userText.toLowerCase(Locale.ROOT);
        List<int[]> literals = literalSpans(hay);

        List<Candidate> matched = new ArrayList<>();
        for (Map.Entry<String, ConnectorSemantic> e : dedupe(metrics).entrySet()) {
            String term = e.getKey();
            int at = firstRealOccurrence(hay, term, literals);
            if (at < 0) continue;
            matched.add(new Candidate(term, at, e.getValue()));
        }
        if (matched.isEmpty()) {
            return empty(cap);
        }

        // 长的排前面 = 更具体的口径优先。「销售额同比」有自己的定义时，它比「销售额」更该被看见；
        // 上限咬下来的时候活下来的也应该是它。长度相同再按出现位置、词条本身定序，保证完全确定。
        matched.sort((a, b) -> {
            int byLen = Integer.compare(b.term().length(), a.term().length());
            if (byLen != 0) return byLen;
            int byPos = Integer.compare(a.at(), b.at());
            if (byPos != 0) return byPos;
            return a.term().compareTo(b.term());
        });

        List<Hit> hits = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        StringBuilder lines = new StringBuilder();
        int used = 0;
        for (Candidate c : matched) {
            if (hits.size() >= cap) {
                omitted.add(c.row().getTerm().trim());
                continue;
            }
            Hit hit = toHit(c.row());
            String line = "\n- " + hit.getTerm() + "：" + hit.getGloss() + "（依据：" + hit.getBasis() + "）";
            // 预算不够就整条不给。★ 绝不截断 gloss：口径最关键的半句往往在末尾
            //（「…但不含预售订单」），砍掉它得到的是一条读起来完整、实际是错的口径。
            // 第一条无论多长都给——只有标题没有条目的块是纯噪声。
            if (!hits.isEmpty() && used + line.length() > budget) {
                omitted.add(c.row().getTerm().trim());
                continue;
            }
            lines.append(line);
            used += line.length();
            hits.add(hit);
        }

        return MetricContext.builder()
                .block(render(lines.toString(), hits.size(), omitted))
                .hits(hits)
                .omitted(omitted)
                .cap(cap)
                .build();
    }

    /** 资格判断单独开出来：调用方可以拿它去写查询条件，两边对"什么叫可强制执行"的定义就不会分叉。 */
    public static boolean isEligible(ConnectorSemantic m) {
        if (m == null) return false;
        // 只认 METRIC。CAVEAT 行装的是【问题】不是答案（「status 取值含义未知」），
        // 把它按"已确认的唯一标准"摆出去，等于把一个待确认项宣布成结论。
        if (!ConnectorSemanticService.SCOPE_METRIC.equals(m.getScope())) return false;
        // 机器推断的口径不存在"正确"这一说：答案不在库里，推出来的都是编的。
        // IMPORTED 同样不收——它给不出"谁在什么时候确认的"，也就兑现不了第五节那条依据要求。
        if (!ConnectorSemanticService.SOURCE_HUMAN.equals(m.getSource())) return false;
        if (!ConnectorSemanticService.ST_CONFIRMED.equals(m.getStatus())) return false;
        // status 是单值字段，CONFIRMED 就不可能同时是 STALE，这一行今天是冗余的，刻意留着：
        // 哪天 status 长出复合态（或者有人加了 CONFIRMED_STALE），少了它就会把一条锚点已经
        // 对不上的口径当成确认过的强制执行，而且不报错。
        if (ConnectorSemanticService.ST_STALE.equals(m.getStatus())) return false;
        return !isBlank(m.getTerm()) && !isBlank(m.getGloss());
    }

    /**
     * 词条归一：{@code trim} + {@code Locale.ROOT} 小写。
     *
     * <p>两处都不能省，而且和 {@code ConnectorToolExecutor} / {@code ConnectorOverviewService}
     * 里那两个同名私有方法<b>必须同形</b>（那两处跨包拿不到，这里刻意是 public，将来应该收敛到这一份）。
     * {@code trim} 是因为库里唯一键的排序规则是 {@code utf8mb4_unicode_ci}（PAD SPACE），
     * 「销售额」和「 销售额 」在库里<b>本来就是同一行</b>。{@code Locale.ROOT} 是因为默认 locale
     * 在土耳其语环境下会把 {@code "ROI"} 小写成 {@code "roı"}（点不见了的那个 i），
     * 于是库里存的 {@code ROI} 和用户敲的 {@code roi} 对不上——同一份代码换个部署地就悄悄失效。
     */
    public static String normalizeTerm(String term) {
        return term == null ? EMPTY : term.trim().toLowerCase(Locale.ROOT);
    }

    // ================================================================ 匹配

    /**
     * 第一个"真的算数"的出现位置，没有返回 -1。
     *
     * <h4>★ 中文没有词边界，这里的取舍要说清楚</h4>
     * 「销售额」出现在「销售额同比」里面，本类<b>算命中</b>。这不是漏掉的 bug，是想清楚的选择：
     * <ul>
     *   <li>要对中文做真正的边界判断只能靠分词，而分词是<b>模糊的</b>——本类的全部价值就在于
     *       "确定性"，引入一个会因为词典版本不同而改变结果的环节，等于把这个价值抵消掉。</li>
     *   <li>更关键的是这个超集命中<b>不会算错数</b>：我们没有改写用户那句话，只是附加了一条
     *       「贵司的『销售额』口径是 X」。用户问同比，销售额的口径照样管着分子分母。</li>
     *   <li>万一「销售额同比」自己也有口径，它更长、排在前面，上限咬下来时活下来的是它。</li>
     * </ul>
     * 注意方向只有一个：我们拿<b>库里的词条</b>去用户那句话里找。所以只可能"短词条命中长句子"，
     * 不可能出现"库里的『净销售额』被用户的『销售额』命中"——那个方向本来就不成立。
     *
     * <h4>拉丁词才需要边界，而且必须要</h4>
     * {@code ROI} 落在 {@code trois} 里、{@code AR} 落在 {@code start} 里，那是纯粹的误命中，
     * 后果是把一条无关口径宣布成唯一标准。所以：<b>词条这一端和相邻那一端同为"分词型字符"
     * （拉丁字母 / 数字 / 下划线）时判定为越界</b>。{@code ROI率} 里的 {@code 率} 是汉字、
     * 不是分词型字符，照常命中。
     */
    private static int firstRealOccurrence(String hay, String term, List<int[]> literals) {
        int from = 0;
        while (from <= hay.length() - term.length()) {
            int at = hay.indexOf(term, from);
            if (at < 0) return -1;
            int end = at + term.length();
            if (boundaryOk(hay, term, at, end) && !insideLiteral(literals, at, end)) {
                return at;
            }
            from = at + 1;
        }
        return -1;
    }

    private static boolean boundaryOk(String hay, String term, int start, int end) {
        if (start > 0 && isWordChar(hay.codePointBefore(start)) && isWordChar(term.codePointAt(0))) {
            return false;
        }
        return !(end < hay.length()
                && isWordChar(hay.codePointAt(end))
                && isWordChar(term.codePointBefore(term.length())));
    }

    /**
     * "分词型字符"：靠空格/标点分词的文字体系里的字母数字。汉字与假名<b>不算</b>——
     * 它们本来就连着写，拿它们当边界就等于永远不命中中文词条。
     */
    private static boolean isWordChar(int cp) {
        if (cp == '_') return true;
        if (!Character.isLetterOrDigit(cp)) return false;
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s != Character.UnicodeScript.HAN
                && s != Character.UnicodeScript.HIRAGANA
                && s != Character.UnicodeScript.KATAKANA;
    }

    /**
     * 成对的字面量引号圈出的区间。
     *
     * <p>三种引号<b>各自配对</b>，互不干扰。落单的引号<b>不开区间</b>：英文里的 {@code it's}
     * 有一个撇号，若让它一直圈到句尾，后面所有词条就全被吞掉了。
     *
     * <p>这个近似会偏向"多圈一点"（双引号内部的一个撇号可能和更后面的撇号配上对），
     * 这是刻意的方向：多圈一点导致漏命中（退回到本阶段之前），少圈一点导致误命中（新增的错）。
     * 见类注释第六节。
     */
    private static List<int[]> literalSpans(String hay) {
        List<int[]> spans = new ArrayList<>();
        for (char q : LITERAL_QUOTES) {
            int from = 0;
            while (true) {
                int open = hay.indexOf(q, from);
                if (open < 0) break;
                int close = hay.indexOf(q, open + 1);
                if (close < 0) break;
                spans.add(new int[]{open, close});
                from = close + 1;
            }
        }
        return spans;
    }

    /** 只有<b>整体落在</b>引号里才算字面量；跨越引号边界的出现仍然算数。 */
    private static boolean insideLiteral(List<int[]> spans, int start, int end) {
        for (int[] s : spans) {
            if (start > s[0] && end <= s[1]) return true;
        }
        return false;
    }

    /**
     * 同名词条只留一条，取 {@code answeredAt} 更晚的那条。{@code LinkedHashMap} 保证遍历序确定。
     *
     * <p>同一条连接上库里的唯一键已经保证不重复，所以走到这里只可能是调用方合并了多条连接——
     * 那是调用点的错，见 {@link #detectWithLimits}。
     */
    private static Map<String, ConnectorSemantic> dedupe(List<ConnectorSemantic> metrics) {
        Map<String, ConnectorSemantic> byTerm = new LinkedHashMap<>();
        for (ConnectorSemantic m : metrics) {
            if (!isEligible(m)) continue;
            String key = normalizeTerm(m.getTerm());
            if (key.isEmpty()) continue;
            ConnectorSemantic prev = byTerm.get(key);
            if (prev == null) {
                byTerm.put(key, m);
            } else if (laterThan(m.getAnsweredAt(), prev.getAnsweredAt())) {
                log.debug("同一个词条出现多条已确认口径，按 answered_at 取较晚的一条 term={}", key);
                byTerm.put(key, m);
            }
        }
        return byTerm;
    }

    private static boolean laterThan(Date a, Date b) {
        if (a == null) return false;
        return b == null || a.after(b);
    }

    // ================================================================ 渲染

    /**
     * 拼成给模型看的那一段。
     *
     * <p>措辞是有意的，每一句都在堵一个具体的失败：
     * <ul>
     *   <li>「精确命中」——告诉模型这不是相似度检索的结果，不要自行判断相关性。</li>
     *   <li>「不要另行推断」——堵住"模型觉得自己的常识更对"。</li>
     *   <li>「把括号中的依据带进答案」——见类注释第五节，这是口径写错之后唯一的发现渠道。</li>
     *   <li>「用户原话未作任何改动」——让模型知道下面那句用户的话是原文，不是被处理过的，
     *       否则它可能以为自己读到的是系统改写过的版本而去"还原"。</li>
     * </ul>
     */
    private static String render(String lines, int shown, List<String> omitted) {
        if (shown == 0) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(BLOCK_HEAD).append("下面 ").append(shown)
                .append(" 条是在用户这句话里【精确命中】的词条，已由贵司的人确认过，是这条连接上的唯一标准。")
                .append("凡本轮回答涉及这些词，一律按此口径写查询，不要另行推断；")
                // ★ 示例里刻意用尖括号占位而不是一个具体的人名和日期：写「9月13日由张三确认」当范例，
                //   模型会把那个不存在的人和日期原样抄进答案——依据这一栏一旦出现编造的人名，
                //   它就从"发现口径错了的唯一渠道"变成了新的错误来源。
                .append("答案里要把括号中的依据原样带上，形如「（口径：<上面那句定义>，<上面括号里的依据>）」。")
                .append("（本段由系统按精确匹配附加，用户原话未作任何改动。）");
        sb.append(lines);
        if (!omitted.isEmpty()) {
            // ★ 被挤掉的必须说出来，而且要点名。静默截断比不注入更糟：模型看不出这份清单是完整的
            //   还是被砍过的，于是会拿"没提到"当"没有口径"。点名之后它至少知道该去 conn_catalog 取。
            sb.append("\n（另有 ").append(omitted.size()).append(" 条已确认口径也命中了这句话，")
                    .append("受本轮注入上限所限未展开：").append(String.join("、", omitted))
                    .append("。需要时调 conn_catalog 查看完整 glossary。）");
        }
        return sb.toString();
    }

    private static Hit toHit(ConnectorSemantic m) {
        return Hit.builder()
                .term(m.getTerm().trim())
                .gloss(oneLine(m.getGloss()))
                .basis(metricBasis(m))
                .build();
    }

    /**
     * 依据那半句。<b>与 {@code ConnectorToolExecutor.metricBasis} 和
     * {@code ConnectorOverviewService.metricBasis} 刻意同形</b>（那两处都是 private，跨包拿不到；
     * 这里是 public static，将来应该由那两处收敛到这一份）。三处措辞要一起改：
     * 同一条口径在目录里、在概览里、在这段强制上下文里显示成三种说法，
     * 业务方就没法确认"这是不是同一条"，而确认这件事正是亮出依据的全部目的。
     */
    public static String metricBasis(ConnectorSemantic m) {
        String who = isBlank(m.getAnsweredName())
                ? "由用户在对话中确认" : "由" + m.getAnsweredName().trim() + "确认";
        if (m.getAnsweredAt() != null) {
            return monthDay(m.getAnsweredAt()) + who;
        }
        if (ConnectorSemanticService.SOURCE_IMPORTED.equals(m.getSource())) {
            return "由管理员导入";
        }
        if (ConnectorSemanticService.SOURCE_HUMAN.equals(m.getSource())) {
            return who;
        }
        return "平台推断，未经人工确认";
    }

    private static String monthDay(Date at) {
        LocalDate d = at.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    /**
     * 压平空白。
     *
     * <p>不只是排版：gloss 是用户自由输入的，里面塞几个换行就能在这段块里<b>伪造出一行条目</b>，
     * 看起来和我们自己生成的那几行一模一样。一行一条目的格式必须由代码保证，不能指望输入干净。
     *
     * <p>残留风险要如实说：口径本身就是"要模型照做"的内容，它落进模型上下文这件事是这个功能的
     * 目的而不是缺陷；这里能做的只是保证<b>结构</b>不被伪造，无法保证一条恶意口径的<b>内容</b>无害。
     * 真正的门槛在"谁能写口径"那一侧。
     */
    private static String oneLine(String s) {
        return s == null ? EMPTY : s.replaceAll("\\s+", " ").trim();
    }

    // ================================================================ 杂项

    private static int clampTerms(int v) {
        return Math.max(HARD_MIN_TERMS, Math.min(HARD_MAX_TERMS, v));
    }

    private static MetricContext empty(int cap) {
        return MetricContext.builder().block(null).hits(List.of()).omitted(List.of()).cap(cap).build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 排序用的中间态，不出这个类。 */
    private record Candidate(String term, int at, ConnectorSemantic row) {}

    // ================================================================ 返回形状

    /**
     * 一次命中的全部结果。
     *
     * <p>用 Lombok {@code @Data} 而不是 record：仓库里 Jackson 是 2.11.1，对外形状一律
     * {@code @Data}（record 的构造参数名在这个版本上不可靠）。
     */
    @Data
    @Builder
    public static class MetricContext {
        /**
         * 拼给模型的那段文本。<b>没有命中时是 {@code null}</b>——调用方不要注入一个只有标题的空块，
         * 那是纯噪声，而且会让模型以为"系统检查过了，这句话里没有任何口径"。
         *
         * <p>★ 它是<b>附加</b>上下文，放在用户原话旁边。任何把它当成"改写后的用户输入"的用法
         * 都违背这个类存在的理由，见类注释第二节。
         */
        private String block;

        /** 真正摆出去的条目，按"更具体的在前"排序。留给调用方打日志 / 审计。 */
        private List<Hit> hits;

        /** 命中了但被上限挤掉的词条（原样的 term）。已经在 {@link #block} 里点过名。 */
        private List<String> omitted;

        /** 本次实际生效的上限（已夹紧）。配置写错时靠它能看出来。 */
        private int cap;

        public boolean hasHits() {
            return hits != null && !hits.isEmpty();
        }
    }

    /** 一条被摆出来的口径。{@code basis} 永远非空，见类注释第五节。 */
    @Data
    @Builder
    public static class Hit {
        private String term;
        private String gloss;
        private String basis;
    }
}
