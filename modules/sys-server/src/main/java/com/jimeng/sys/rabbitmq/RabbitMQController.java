package com.jimeng.sys.rabbitmq;

import com.jimeng.common.core.entity.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author Moonlight
 * @Description 系统控制器
 * @Date 2024/7/25 21:13
 */

@Tag(name = "RabbitMQ管理", description = "RabbitMQ相关接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/sys/rabbit")
public class RabbitMQController {

    private final RabbitAdmin rabbitAdmin;

    @Operation(summary = "删除队列", description = "删除指定的RabbitMQ队列")
    @GetMapping("/del-queue")
    public CommonResponse.Resp delQueue(@Parameter(description = "队列名称") String queueName) {
        boolean result = rabbitAdmin.deleteQueue(queueName);
        return CommonResponse.Resp.newBuilder().setSuccess(result).setRespMsg(result ? "队列删除成功" : "队列删除失败").build();
    }

}
