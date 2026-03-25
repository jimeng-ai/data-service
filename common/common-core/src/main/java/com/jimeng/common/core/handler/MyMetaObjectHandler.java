package com.jimeng.common.core.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;
import java.util.Optional;

/**
 * @Author Moonlight
 * @Description MyBatis-Plus 字段自动填充处理器
 * @Date 2024/10/19 15:00
 */

@Slf4j
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        // 创建时间
        this.strictInsertFill(metaObject, "createTime", Date.class, new Date());
        // 更新时间
        this.strictInsertFill(metaObject, "updateTime", Date.class, new Date());
        // 逻辑删除标志
        this.strictInsertFill(metaObject, "deleted", Boolean.class, false);

        // 获取当前用户信息
        String userId = getCurrentUserId();
        if (userId != null) {
            // 创建人
            this.strictInsertFill(metaObject, "createUser", String.class, userId);
            // 更新人
            this.strictInsertFill(metaObject, "updateUser", String.class, userId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新时间
        this.strictUpdateFill(metaObject, "updateTime", Date.class, new Date());

        // 获取当前用户信息
        String userId = getCurrentUserId();
        if (userId != null) {
            // 更新人
            this.strictUpdateFill(metaObject, "updateUser", String.class, userId);
        }
    }

    /**
     * 获取当前用户ID
     */
    private String getCurrentUserId() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                return Optional.ofNullable(request.getHeader("user-id")).orElse(null);
            }
        } catch (Exception e) {
            log.warn("获取当前用户ID失败: {}", e.getMessage());
        }
        return null;
    }

}
