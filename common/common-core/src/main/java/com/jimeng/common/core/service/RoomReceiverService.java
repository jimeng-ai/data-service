package com.jimeng.common.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/11/2 13:10
 */

@Slf4j
@Service
public class RoomReceiverService {
    public void receiveMessage(Object message) {
        log.info("===========");
        log.info("接收到消息：{}", message);

    }
}
