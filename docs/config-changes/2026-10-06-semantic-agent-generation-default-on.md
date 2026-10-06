# 语义层默认走 agent 生成

- 依据：用户 2026-10-06 定，语义层说明书默认用 agent 生成。
- 代码改动：`connector.semantic.agent.enabled` 的默认值从 `false` 改成 `true`（data-service v1.2.2）。
- 为什么要改：这个开关从前默认关，也没有任何变更文档让人打开，所以各环境一直在走单次推导。单次推导是一次调用写完整个库，表一多就被截断（本地 46 张表就截了）。agent 生成在沙箱里每批最多处理 20 张表，不会被截断。

## 一、上线前：生产 Nacos `data-server.yml`

agent 生成还要下面这几项，没配就用不上（见第三节）：

```yaml
connector:
  semantic:
    agent:
      callback-base-url: <生产网关地址，含 /data>
      llm:
        base-url: https://api.deepseek.com/anthropic
        auth-token: ${providers.deepseek.api-key}
        model: deepseek-flash
```

- `callback-base-url`：和 `connector.agent.callback-base-url` 填同一个值。如果那个也没配过，生产网关地址按代码注释推断是 `http://localhost:20011/data`，上线前请核实。
- `auth-token`：直接写上面这个占位符，引用已有的 DeepSeek key，不要再抄一份。
- 前提：
  - 沙箱已上线到支持语义层运行的版本（v1.1.0 及以后）。
  - `agent.sandbox.base-url` 和 `agent.sandbox.service-token` 已配好。对话功能本来就依赖这两项，一般已经有了。
  - DeepSeek 账户有余额。

## 二、上线后验证

- 在任意一条连接上点「重新生成」。
- 页面上「最新说明」显示「正在生成：已完成 x/y 张表」。
- data-server 日志里不出现「语义层生成走单次推导」。

## 三、没配好会怎样

不会出错，会退回单次推导：「最新说明」里写「已用备用方式生成」，data-server 日志写明原因，比如回调地址没配、模型配置不完整、沙箱不可用。

## 四、回退

在 Nacos 里配 `connector.semantic.agent.enabled: false`，所有连接就会改回单次推导，不需要回退代码。
