package com.jimeng.sys.env.service;

import com.jimeng.persistence.entity.SysEnv;
import com.jimeng.persistence.mapper.SysEnvMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/11/17 14:41
 */

@Service
@Slf4j
@RequiredArgsConstructor
public class SysEnvService {

    private final SysEnvMapper sysEnvMapper;

    public void save(SysEnv sysEnv) {
        sysEnvMapper.insert(sysEnv);
    }

    public void saveBatch(List<SysEnv> sysEnvList) {
        sysEnvList.forEach(sysEnvMapper::insert);
    }

    public Map<String, Map<String, SysEnv>> getEnvMap() {
        Map<String, Map<String, SysEnv>> map = new HashMap<>();
        List<SysEnv> sysEnvList = sysEnvMapper.selectList(null);
        for (SysEnv sysEnv : sysEnvList) {
            if (map.get(sysEnv.getModuleName()) == null) {
                Map<String, SysEnv> hashMap = new HashMap<>();
                hashMap.put(sysEnv.getPropertyName(), sysEnv);
                map.put(sysEnv.getModuleName(), hashMap);
            } else {
                map.get(sysEnv.getModuleName()).put(sysEnv.getPropertyName(), sysEnv);
            }
        }
        return map;
    }
}
