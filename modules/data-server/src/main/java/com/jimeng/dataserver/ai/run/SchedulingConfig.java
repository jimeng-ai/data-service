package com.jimeng.dataserver.ai.run;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务能力。data-server 此前未用 @EnableScheduling，这里独立开启，
 * 仅用于 {@link OrphanRunReconciler} 的孤儿生成清理；新增 @Scheduled 请确认其执行器/频率。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
