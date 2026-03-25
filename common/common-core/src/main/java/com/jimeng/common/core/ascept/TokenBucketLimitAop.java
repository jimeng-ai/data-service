package com.jimeng.common.core.ascept;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.RateLimiter;
import com.jimeng.common.core.annotation.TokenBucketLimit;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * @Author Moonlight
 * @Description 限流逻辑
 * @Date 2024/7/14 10:01
 */

@Slf4j
@Aspect
@Component
public class TokenBucketLimitAop {

    /**
     * 不同的接口，不同的流量控制
     * map的key为 Limiter.key
     */
    private final Map<String, RateLimiter> limitMap = Maps.newConcurrentMap();

    @Around("@annotation(com.jimeng.common.core.annotation.TokenBucketLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        // 拿 limit 的注解
        TokenBucketLimit limit = method.getAnnotation(TokenBucketLimit.class);
        if (limit != null) {
            //key作用：不同的接口，不同的流量控制
            String key = limit.key();
            RateLimiter rateLimiter = null;
            // 验证缓存是否有命中key
            // 做加锁处理，防止重复创建令牌桶
            synchronized (limitMap) {
                if (!limitMap.containsKey(key)) {
                    // 创建令牌桶
                    rateLimiter = RateLimiter.create(limit.permitsPerSecond());
                    limitMap.put(key, rateLimiter);
                    log.info("新建了令牌桶={}，容量={}", key, limit.permitsPerSecond());
                }
            }
            rateLimiter = limitMap.get(key);
            // 拿令牌
            boolean acquire = rateLimiter.tryAcquire(limit.timeout(), limit.timeunit());
            if (acquire) {
                log.info("令牌桶={}，获取令牌成功，配置速率={}", key, rateLimiter.getRate());
            }
            // 拿不到命令，直接返回异常提示
            if (!acquire) {
                log.error("令牌桶={}，获取令牌失败", key);
                throw new ServiceException(ExceptionCode.SERVER_BUSY);
            }
        }
        return joinPoint.proceed();
    }

}
