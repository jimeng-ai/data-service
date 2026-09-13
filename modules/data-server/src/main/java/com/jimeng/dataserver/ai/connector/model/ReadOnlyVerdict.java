package com.jimeng.dataserver.ai.connector.model;

/**
 * 只读验证的判定。
 *
 * <p><b>三态不是两态</b>——这是本类唯一重要的设计：{@code readOnly=false} 与
 * {@code undetermined=true} 必须分开。前者是「确认这个账号能写」，后者是「探测没跑成，不知道」。
 * 把「不知道」当成「只读」就等于没验；把它当成「能写」又会误伤。
 * 两者都必须<b>拒绝保存</b>，但给客户的话术不同。
 *
 * @param readOnly     确认只读（无害写操作被拒绝了）
 * @param undetermined 无法判定
 * @param detail       给人看的说明，已脱敏
 */
public record ReadOnlyVerdict(boolean readOnly, boolean undetermined, String detail) {

    public static ReadOnlyVerdict confirmed(String detail) {
        return new ReadOnlyVerdict(true, false, detail);
    }

    /** 写操作成功或被允许——账号不是只读的。 */
    public static ReadOnlyVerdict writable(String detail) {
        return new ReadOnlyVerdict(false, false, detail);
    }

    public static ReadOnlyVerdict unknown(String detail) {
        return new ReadOnlyVerdict(false, true, detail);
    }

    /** 只有确认只读才准保存。「不知道」一律按不通过——fail-closed。 */
    public boolean acceptable() {
        return readOnly && !undetermined;
    }
}
