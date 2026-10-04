package com.jimeng.dataserver.admin.auth.service;

import com.jimeng.common.core.security.JwtSecretProvider;
import com.jimeng.dataserver.admin.operator.auth.service.OperatorAuthService;
import com.jimeng.persistence.entity.SysOperator;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import com.jimeng.persistence.mapper.SysOperatorMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录限流必须按「库认定的账号」计数：username 列是 utf8mb4_unicode_ci，ádmin、ＡＤＭＩＮ、admin 命中的是同一行，
 * 只按输入字符串计数的话，换个写法就是一本新账，限流形同虚设。
 */
class LoginAttemptKeyTest {

    @Test
    void enterpriseVariantsOfAnExistingAccountShareOneKey() {
        SysUserMapper users = mock(SysUserMapper.class);
        SysUser admin = new SysUser();
        admin.setId(7L);
        when(users.selectOne(any())).thenReturn(admin);   // 库按排序规则把各种写法都匹配到同一行
        AdminAuthService service = new AdminAuthService(users, mock(SysEnterpriseMapper.class),
                new BCryptPasswordEncoder(), new JwtSecretProvider());

        assertEquals("id:7", service.loginAttemptKey("admin"));
        assertEquals("id:7", service.loginAttemptKey("ádmin"));
        assertEquals("id:7", service.loginAttemptKey("ＡＤＭＩＮ"));
    }

    @Test
    void enterpriseUnknownAccountFallsBackToNormalizedInput() {
        SysUserMapper users = mock(SysUserMapper.class);
        when(users.selectOne(any())).thenReturn(null);
        AdminAuthService service = new AdminAuthService(users, mock(SysEnterpriseMapper.class),
                new BCryptPasswordEncoder(), new JwtSecretProvider());

        assertEquals("name:bob", service.loginAttemptKey("  Bob "));
        assertEquals("name:", service.loginAttemptKey(null));
        assertEquals("name:", service.loginAttemptKey("   "));
    }

    @Test
    void operatorVariantsOfAnExistingAccountShareOneKey() {
        SysOperatorMapper operators = mock(SysOperatorMapper.class);
        SysOperator admin = new SysOperator();
        admin.setId(3L);
        when(operators.selectOne(any())).thenReturn(admin);
        OperatorAuthService service = new OperatorAuthService(operators, new BCryptPasswordEncoder(), new JwtSecretProvider());

        assertEquals("id:3", service.loginAttemptKey("admin"));
        assertEquals("id:3", service.loginAttemptKey("ＡＤＭＩＮ"));
    }

    @Test
    void operatorUnknownAccountFallsBackToNormalizedInput() {
        SysOperatorMapper operators = mock(SysOperatorMapper.class);
        when(operators.selectOne(any())).thenReturn(null);
        OperatorAuthService service = new OperatorAuthService(operators, new BCryptPasswordEncoder(), new JwtSecretProvider());

        assertEquals("name:ops", service.loginAttemptKey("OPS"));
    }
}
