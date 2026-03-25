package com.jimeng.api;

import com.jimeng.persistence.entity.SysEnv;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/**
 * @Author Moonlight
 * @Description 系统服务的api接口
 * @Date 2024/8/18 13:34
 */

@FeignClient(contextId = "sysService", name = "sys-server")
public interface SysApi {

    @GetMapping("/admin/sys/env/get-env-map")
    public Map<String, Map<String, SysEnv>> getEnvMap();

}
