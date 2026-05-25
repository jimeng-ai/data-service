package com.jimeng.common.core.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.jimeng.common.core.tenant.JimengTenantLineHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author Moonlight
 * @Description MyBatis-Plus 配置类
 * @Date 2024/11/16 12:10
 */

@Configuration
@MapperScan("com.jimeng.persistence.mapper")
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 插件配置
     *
     * <p>InnerInterceptor 注册顺序遵循官方建议：多租户 → 分页 → 乐观锁 → 防全表更新。
     * 多租户必须最先：先把 SQL 拼上 {@code WHERE tenant_id = ?}，后面的分页/防全表更新拦截器再处理。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(JimengTenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 多租户插件：基于正向白名单只过滤声明的租户隔离表（见 JimengTenantLineHandler）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));

        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        // 乐观锁插件
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 防止全表更新与删除插件
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

}
