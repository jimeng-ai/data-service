package com.jimeng.common.core.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * @Author Moonlight
 * @Description 限流注解
 * @Date 2024/7/14 9:59
 */

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
@Documented
public @interface TokenBucketLimit {

    /**
     * 资源的key,唯一
     * 作用：不同的接口，不同的流量控制
     */
    String key() default "";

    /**
     * 最多的访问限制次数
     */
    double permitsPerSecond() default 100;

    /**
     * 获取令牌最大等待时间
     */
    long timeout() default 1000;

    /**
     * 获取令牌最大等待时间,单位(例:分钟/秒/毫秒) 默认:毫秒
     */
    TimeUnit timeunit() default TimeUnit.MILLISECONDS;

    /**
     * 得不到令牌的提示语
     */
    String msg() default "系统繁忙,请稍后再试.";

}
