package com.jimeng.common.core.handler;

import com.jimeng.common.core.entity.common.CommonResponse;
import com.jimeng.common.core.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * @Author Moonlight
 * @Description 全局异常处理器
 * @Date 2024/8/4 13:27
 */

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public CommonResponse.Resp handleServiceException(ServiceException ex) {
        return CommonResponse.Resp.newBuilder().setRespMsg(ex.getRespMsg()).setRespCode(ex.getRespCode()).setSuccess(Boolean.FALSE).build();
    }

}
