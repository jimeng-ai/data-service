package com.jimeng.dataserver.admin.auth.init;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.persistence.entity.SysAdmin;
import com.jimeng.persistence.mapper.SysAdminMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时若 sys_admin 表里没有 admin 账号，写入默认账号 admin / admin123。
 * <p>BCrypt hash 由真实 encoder 当场生成，避免在 SQL 里硬编码错的 hash。
 * <p>默认账号上线后请尽快通过 /data/admin/auth/change-password 改密。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuthInitializer implements CommandLineRunner {

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";
    private static final String DEFAULT_DISPLAY_NAME = "超级管理员";
    private static final String DEFAULT_TENANT_ID = "default";

    private final SysAdminMapper sysAdminMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        Long existing = sysAdminMapper.selectCount(
                Wrappers.<SysAdmin>lambdaQuery().eq(SysAdmin::getUsername, DEFAULT_USERNAME));
        if (existing != null && existing > 0) {
            return;
        }
        SysAdmin admin = new SysAdmin();
        admin.setTenantId(DEFAULT_TENANT_ID);
        admin.setUsername(DEFAULT_USERNAME);
        admin.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        admin.setDisplayName(DEFAULT_DISPLAY_NAME);
        admin.setStatus(1);
        admin.setCreateUser("system");
        admin.setUpdateUser("system");
        sysAdminMapper.insert(admin);
        log.info("已初始化默认管理员账号 username={} （请尽快修改密码）", DEFAULT_USERNAME);
    }
}
