package com.jimeng.common.core.exception;

import com.jimeng.common.core.enums.ExceptionCode;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author Moonlight
 * @Description 异常处理类
 * @Date 2024/7/13 20:57
 */

@Slf4j
public class ServiceException extends RuntimeException {

    private final String respCode;
    private final String respMsg;

    public ServiceException(Throwable cause, String respCode, String respMsg) {
        super(respMsg + "[" + respCode + "]", cause);
        log.error("{}[{}]", respMsg, respCode);
        this.respCode = respCode;
        this.respMsg = respMsg;
    }

    public ServiceException(String respCode, String respMsg) {
        this((Throwable) null, respCode, (String) respMsg);
    }

    public ServiceException(ExceptionCode errorCode) {
        this(errorCode.getResultCode(), errorCode.getResultMsg());
    }

    public ServiceException(ExceptionCode errorCode, String detailInfo) {
        this(errorCode.getResultCode(), detailInfo);
    }

    public ServiceException(ExceptionCode errorCode, String respMsg, Object... args) {
        this(errorCode.getResultCode(), String.format(respMsg, args));
    }

    public String getRespCode() {
        return this.respCode;
    }

    public String getRespMsg() {
        return this.respMsg;
    }

}
