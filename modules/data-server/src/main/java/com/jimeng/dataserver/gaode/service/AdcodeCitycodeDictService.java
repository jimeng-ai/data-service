package com.jimeng.dataserver.gaode.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.persistence.entity.AdcodeCitycodeDict;
import com.jimeng.persistence.mapper.AdcodeCitycodeDictMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * @Author Moonlight
 * @Description 行政区编码字典服务
 * @Date 2026/3/29 13:30
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdcodeCitycodeDictService {

    private final AdcodeCitycodeDictMapper adcodeCitycodeDictMapper;

    public AdcodeCitycodeDict getById(Long id) {
        if (id == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "id不能为空");
        }
        return adcodeCitycodeDictMapper.selectById(id);
    }

    public List<AdcodeCitycodeDict> list(AdcodeCitycodeDict condition) {
        if (condition == null) {
            condition = new AdcodeCitycodeDict();
        }
        LambdaQueryWrapper<AdcodeCitycodeDict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(condition.getId() != null, AdcodeCitycodeDict::getId, condition.getId())
                .eq(condition.getSortNo() != null, AdcodeCitycodeDict::getSortNo, condition.getSortNo())
                .like(StringUtils.hasText(condition.getNameCn()), AdcodeCitycodeDict::getNameCn, condition.getNameCn())
                .eq(StringUtils.hasText(condition.getAdcode()), AdcodeCitycodeDict::getAdcode, condition.getAdcode())
                .eq(StringUtils.hasText(condition.getCitycode()), AdcodeCitycodeDict::getCitycode, condition.getCitycode())
                .orderByAsc(AdcodeCitycodeDict::getSortNo)
                .orderByAsc(AdcodeCitycodeDict::getId);
        return adcodeCitycodeDictMapper.selectList(wrapper);
    }

    public void updateById(AdcodeCitycodeDict adcodeCitycodeDict) {
        if (adcodeCitycodeDict.getId() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "更新时id不能为空");
        }
        adcodeCitycodeDictMapper.updateById(adcodeCitycodeDict);
    }

}
