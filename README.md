README

# 基础工程模板

可基于此工程模板快速搭建一个SpringCloud项目

## ✨ 功能特性
- 待完善

## 🚀 快速开始

### 环境要求
- JDK 8+
- MySQL 8.0+
- Maven 3.6+

### 依赖版本介绍

- Redisson：3.13.4
- hutool：5.8.16
- lombok：1.18.24
- spring-boot：2.6.6
- spring-cloud：2021.0.2
- spring-cloud-alibaba：2021.0.1.0
- querydsl：5.0.0

### 目录结构

- common：通用模块组件
  - common-core：核心组件，里面包含自定义注解、配置类、异常处理类、Redisson、工具类等
  - common-identifier：雪花算法生成器
  - common-persistence：数据库实体类以及数据库操作层
- export：对外的api接口以及feign调用接口
- gateway：网关，包括token权限校验
- modeuls：业务模块
  - sys-server：系统模块，例如系统字典、系统变量等（可选，可以根据自己的业务场景调整）

### Nacos配置说明

- default-mysql.yml：mysql配置
- default-redis.yml：redis配置
- default-rabbitmq.yml：rabbitmq配置
- gateway.yml：网关配置
- sys-server.yml：sys服务配置（可选）