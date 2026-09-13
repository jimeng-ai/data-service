package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorPendingWrite;
import org.apache.ibatis.annotations.Mapper;

/**
 * 待审批写操作 Mapper。
 *
 * <p><b>状态流转一律走「带条件的 update」，不要先查再改。</b>
 * {@code UPDATE ... WHERE id = ? AND status = 'PENDING'} 的返回行数就是「有没有抢到」：
 * 两个超管同时点批准时，只有一个人能拿到 1，另一个拿到 0 —— 这是本表唯一的并发防线。
 * 换成「先 selectById 判 status、再 updateById」，两个线程会双双通过判断，然后<b>执行两次写</b>。
 *
 * <p>不需要自定义 SQL：BaseMapper 的 {@code update(null, LambdaUpdateWrapper)} 就返回行数。
 */
@Mapper
public interface ConnectorPendingWriteMapper extends BaseMapper<ConnectorPendingWrite> {
}
