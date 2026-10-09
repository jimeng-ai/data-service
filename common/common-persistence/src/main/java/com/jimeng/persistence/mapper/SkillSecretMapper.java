package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.SkillSecret;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkillSecretMapper extends BaseMapper<SkillSecret> {

    /**
     * 删掉一个 skill 所有版本的 key——<b>物理删除</b>，密文不再留在表里。
     * BaseMapper 的 delete 对继承了 {@code BaseEntity}（带 {@code @TableLogic}）的实体只是打删除标记，密文还在；
     * 密钥表不该这样：将来库和 CONNECTION_CREDENTIAL_KEY 一起泄露时，已经删掉的 skill 的 key 不该还能解开。
     * 租户拦截器照样追加 tenant_id 条件（它按语句类型改写所有 INSERT / UPDATE / DELETE，不管 SQL 从哪来，
     * 见 SkillSecretMapperTenantTest）。
     */
    @Delete("DELETE FROM skill_secret WHERE skill_id = #{skillId}")
    int deleteBySkillId(@Param("skillId") Long skillId);
}
