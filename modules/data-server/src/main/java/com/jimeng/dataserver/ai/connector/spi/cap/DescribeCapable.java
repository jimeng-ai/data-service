package com.jimeng.dataserver.ai.connector.spi.cap;

import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;

import java.util.Collection;
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
}
