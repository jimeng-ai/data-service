package com.jimeng.common.core.entity.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/10/3 13:51
 */

public interface PageEntity {

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    class PageReq {
        private int pageNum = 1;
        private int pageSize = 10;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    class PageResp {
        private Long total;
    }

}
