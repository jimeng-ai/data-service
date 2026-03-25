package com.jimeng.sys.dict.service;

import com.jimeng.persistence.entity.SysDict;
import com.jimeng.persistence.mapper.SysDictMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/11/17 15:10
 */

@Service
@Slf4j
@RequiredArgsConstructor
public class SysDictService {

    private final SysDictMapper sysDictMapper;

    public void save(SysDict sysDict) {
        sysDictMapper.insert(sysDict);
    }

    public void saveBatch(List<SysDict> sysDictList) {
        sysDictList.forEach(sysDictMapper::insert);
    }
}
