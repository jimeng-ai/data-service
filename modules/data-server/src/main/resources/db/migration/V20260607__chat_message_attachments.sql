-- 用户消息携带的上传附件（图片/文档/表格），用于刷新或重进会话后仍能显示缩略图并预览。
-- 仅存元信息（fileId / filename / contentType）；预览内容经 GET /data/agent/files/{fileId} 取流。
ALTER TABLE `chat_message`
    ADD COLUMN `attachments` JSON DEFAULT NULL COMMENT '消息附件列表（fileId/filename/contentType，JSON，可空）' AFTER `segments`;
