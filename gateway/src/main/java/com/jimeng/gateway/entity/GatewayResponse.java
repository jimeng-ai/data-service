package com.jimeng.gateway.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author Moonlight
 * @Description 通用结果响应类
 * @Date 2024/7/13 15:38
 */

public interface GatewayResponse {

    @Data
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    @NoArgsConstructor
    @AllArgsConstructor
    class Resp{
        private Boolean success;
        private String respCode;
        private String respMsg;
        private Object data;
    }

}
