package com.jimeng.dataserver.ai.connector.spi.cap;

import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 能自描述：让 Agent 知道「这里面有什么」。
 *
 * <p>刻意分成两段，因为大库有几百张表、结构全量注入放不下，而让 Agent 从零探索又浪费轮次：
 * {@link #catalog()} 给一份<b>便宜的</b>目录级概览，{@link #describe} 按需取某个对象的细节。
 */
public interface DescribeCapable {

    /** 目录概览：有哪些表 / 接口 / 目录 / 主题。只给名字和一句注释。 */
    CatalogView catalog();

    /**
     * 某个对象的细节。
     *
     * @param object 对象标识，取自 {@link CatalogView} 里的条目名
     */
    ObjectDetail describe(String object);

    /**
     * 这几个名字里<b>此刻真实存在</b>的是哪些（契约 K-1）。
     *
     * <h3>为什么需要它</h3>
     * 目录本身被截断（超过上限）时，没列出来的名字只能判「不知道」。而漂移检测对「不知道」一个字不碰——
     * 于是一张被删掉的表，只要库里的对象数超过目录上限，就<b>永远</b>不会被判成消失：挂在它上面的说明、关系、
     * 口径一直注入，而且没有任何信号。结构快照刷新时，对「上一次见过、这一次目录里没有」的那一小撮名字
     * 单独问一句存不存在，就能把「不知道」收窄成「在」或「没了」。
     *
     * <h3>三态返回，{@code null} 是正当答案</h3>
     * <ul>
     *   <li>返回集合：入参里存在的那些（按连接器里存储的写法），其余的就是确认不存在；</li>
     *   <li>返回 {@code null}：这个连接器<b>答不了</b>。调用方必须把它当「不知道」，绝不能当「全都不存在」——
     *       把「答不了」读成「都没了」，会让整条连接的说明书在一次刷新里全部过期。</li>
     * </ul>
     * 默认实现返回 {@code null}：没实现这件事的连接器维持从前的行为，不会凭空多出一批「消失的表」。
     *
     * <p>调用方在拉结构快照的<b>同一个会话</b>里调用它，与目录、描述同处一次网关往返（同一份审计、同一个并发许可）。
     *
     * @param names 要确认的对象名；为空时实现可以直接返回空集合
     * @return 存在的那部分名字；{@code null} = 不知道
     */
    default Set<String> existingObjects(Collection<String> names) {
        return null;
    }

    /**
     * 这几个对象里，哪些<b>此刻至少有一行数据</b>、哪些<b>确认一行都没有</b>（缺陷 B19）。
     *
     * <h3>为什么要探这一下</h3>
     * POC 环境里 {@code D1_COMPANYCODE} 与 {@code EMM_PURCHASEORDERCONFIRM} 是 <b>0 行</b>。
     * 模型 join 到这样一张空维表，拿回 0 行，然后把「没有数据」当成业务答案报给用户：
     * 查询成功、没有报错、数字静默地错。结构快照里存着列名、类型、注释，
     * 唯独没有「这张表里到底有没有东西」——而它恰恰是把「空结果」读成「答案」还是读成
     * 「这张表是空的」的唯一判据。
     *
     * <h3>三态，{@code null} 与「键不在」都是正当答案</h3>
     * <ul>
     *   <li>{@code TRUE}：至少有一行；</li>
     *   <li>{@code FALSE}：<b>确认</b>一行都没有；</li>
     *   <li>键不在 map 里、或整个返回是 {@code null}：<b>不知道</b>（连接器答不了、超时、没权限、预算用完）。</li>
     * </ul>
     * <b>「没探到」绝不能记成「是空的」</b>：把一张有数据的表说成空表，比什么都不说更糟——
     * 模型会据此告诉用户「这张表里没有数据」，而那是一句凭空编出来的结论。
     * 默认实现返回 {@code null}：没实现这件事的连接器维持从前的行为，不会凭空多出一批「空表」。
     *
     * <h3>实现必须命中即停，并自己兜住成本上限</h3>
     * 只需要一个布尔，所以探的是 {@code SELECT 1 FROM <表> LIMIT 1} 这类<b>命中即停</b>的语句：
     * <ul>
     *   <li><b>不要用 {@code COUNT(*)}</b>——大表上那是一次全表扫，为了一个布尔付全表的代价；</li>
     *   <li><b>不要信 {@code information_schema.TABLE_ROWS}</b>——InnoDB 那一列是抽样估算值，
     *       实测同一张表两次查能差几倍，对「是不是恰好 0 行」并不可靠（它可能对一张空表报出非 0，
     *       也可能对一张有数据的表报 0）。这里要的恰恰是那个精确的边界。</li>
     * </ul>
     * 调用方会给一个<b>整批的时间预算</b>，实现必须在预算内收手、把没探到的名字<b>留成「不知道」</b>，
     * 而不是把一次刷新拖成几分钟。
     *
     * <p>调用方在拉结构快照的<b>同一个会话</b>里调用它，与目录、描述、存在性确认同处一次网关往返
     * （同一份审计、同一个并发许可）。
     *
     * @param names         要探的对象名；为空时实现可以直接返回空 map
     * @param budgetMillis  这一整批的挂钟预算（毫秒）。用完就收手，剩下的按「不知道」处理
     * @return 名字 → 是否至少有一行；缺键 = 不知道。整个返回 {@code null} = 这个连接器答不了
     */
    default Map<String, Boolean> probeRowPresence(Collection<String> names, long budgetMillis) {
        return null;
    }
}
