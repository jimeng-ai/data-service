package com.jimeng.dataserver.ai.provider.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RerankHit {

    /** 在原始 documents 数组中的索引 */
    private int index;

    /** 相关性分数（不同模型量纲不同，仅用于排序） */
    private double relevanceScore;
}
