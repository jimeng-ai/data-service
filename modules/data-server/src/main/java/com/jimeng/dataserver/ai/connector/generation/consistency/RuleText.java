package com.jimeng.dataserver.ai.connector.generation.consistency;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 规则文案里反复出现的几种写法。只管措辞，不含任何判定。 */
final class RuleText {

    private RuleText() {
    }

    /** {@code A.x → B.y}：关系的写法，所有 JOIN 规则同一种，agent 在多条退回原因之间才对得上是同一条关系。 */
    static String arrow(String leftObject, String leftColumn, String toObject, String toColumn) {
        return leftObject + "." + leftColumn + " → " + toObject + "." + toColumn;
    }

    /** [a] → a；[a, b] → a 和 b；[a, b, c] → a、b 和 c。 */
    static String joinAnd(Collection<String> items) {
        List<String> list = new ArrayList<>(items);
        if (list.size() <= 1) {
            return String.join("", list);
        }
        return String.join("、", list.subList(0, list.size() - 1)) + " 和 " + list.get(list.size() - 1);
    }
}
