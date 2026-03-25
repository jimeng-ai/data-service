package com.jimeng.common.core.utils;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * @Author Moonlight
 * @Description 请求工具类
 * @Date 2024/7/19 8:16
 */

public class RequestUtil {

    private static String getUserId() {
        HttpServletRequest request = currentRequest();
        return Optional.ofNullable(request.getHeader("user-id")).orElse(null);
    }

    private static HttpServletRequest currentRequest() {
        return (HttpServletRequest)Optional.ofNullable((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).map(ServletRequestAttributes::getRequest).orElseThrow(() -> {
            return new ServiceException(ExceptionCode.INVALID_REQUEST, "无效请求", new Object[0]);
        });
    }

    public static String getCurrentUserId() {
        return getUserId();
    }

    public static Boolean isLogin(){
        if (getCurrentUserId() == null){
            return false;
        }
        return true;
    }

}
