package com.jimeng.dataserver.admin.operator.auth.init;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.persistence.entity.SysOperator;
import com.jimeng.persistence.mapper.SysOperatorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时若 sys_operator 表里没有 admin 账号，写入默认运营账号 admin / admin123。
 * <p>BCrypt hash 由真实 encoder 当场生成。默认账号上线后请尽快通过
 * /data/admin/operator/auth/change-password 改密。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperatorAuthInitializer implements CommandLineRunner {

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";
    private static final String DEFAULT_DISPLAY_NAME = "平台运营";

    private final SysOperatorMapper sysOperatorMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        Long existing = sysOperatorMapper.selectCount(
                Wrappers.<SysOperator>lambdaQuery().eq(SysOperator::getUsername, DEFAULT_USERNAME));
        if (existing != null && existing > 0) {
            return;
        }
        SysOperator op = new SysOperator();
        op.setUsername(DEFAULT_USERNAME);
        op.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        op.setDisplayName(DEFAULT_DISPLAY_NAME);
        op.setStatus(1);
        op.setCreateUser("system");
        op.setUpdateUser("system");
        sysOperatorMapper.insert(op);
        log.info("已初始化默认运营账号 username={} （请尽快修改密码）", DEFAULT_USERNAME);
    }
}
