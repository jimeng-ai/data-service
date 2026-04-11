package com.jimeng.dataserver.gaode.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.persistence.entity.PoiCategoryDict;
import com.jimeng.persistence.mapper.PoiCategoryDictMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * @Author Moonlight
 * @Description POI分类字典服务
 * @Date 2026/3/28 13:18
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PoiCategoryDictService {

    private final PoiCategoryDictMapper poiCategoryDictMapper;

    public PoiCategoryDict getById(Long id) {
        if (id == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "id不能为空");
        }
        return poiCategoryDictMapper.selectById(id);
    }

    public List<PoiCategoryDict> list(PoiCategoryDict condition) {
        if (condition == null) {
            condition = new PoiCategoryDict();
        }
        LambdaQueryWrapper<PoiCategoryDict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(condition.getId() != null, PoiCategoryDict::getId, condition.getId())
                .eq(condition.getSortNo() != null, PoiCategoryDict::getSortNo, condition.getSortNo())
                .eq(StringUtils.hasText(condition.getNewType()), PoiCategoryDict::getNewType, condition.getNewType())
                .like(StringUtils.hasText(condition.getBigCategoryCn()), PoiCategoryDict::getBigCategoryCn, condition.getBigCategoryCn())
                .like(StringUtils.hasText(condition.getMidCategoryCn()), PoiCategoryDict::getMidCategoryCn, condition.getMidCategoryCn())
                .like(StringUtils.hasText(condition.getSubCategoryCn()), PoiCategoryDict::getSubCategoryCn, condition.getSubCategoryCn())
                .like(StringUtils.hasText(condition.getBigCategoryEn()), PoiCategoryDict::getBigCategoryEn, condition.getBigCategoryEn())
                .like(StringUtils.hasText(condition.getMidCategoryEn()), PoiCategoryDict::getMidCategoryEn, condition.getMidCategoryEn())
                .like(StringUtils.hasText(condition.getSubCategoryEn()), PoiCategoryDict::getSubCategoryEn, condition.getSubCategoryEn())
                .orderByAsc(PoiCategoryDict::getSortNo)
                .orderByAsc(PoiCategoryDict::getId);
        return poiCategoryDictMapper.selectList(wrapper);
    }

    public void updateById(PoiCategoryDict poiCategoryDict) {
        if (poiCategoryDict.getId() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "更新时id不能为空");
        }
        poiCategoryDictMapper.updateById(poiCategoryDict);
    }
}
