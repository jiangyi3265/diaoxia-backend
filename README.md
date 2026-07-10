# 钓虾业务系统后端

## 项目简介

钓虾业务系统的后端服务，为管理后台与用户端提供统一的 REST API、认证授权、会员、预约、订单、商城及运营管理等核心业务能力。

## 技术栈

- Java 8、Spring Boot 2.5、Spring Security
- MyBatis、Druid、Maven
- MySQL、Redis、Swagger / Springfox

## 关联仓库

| 仓库 | 职责 | 与本仓库的关系 |
| --- | --- | --- |
| [diaoxia-backend](https://github.com/jiangyi3265/diaoxia-backend) | 后端服务 | 当前仓库，为其他端提供 API 与业务能力 |
| [diaoxia-admin](https://github.com/jiangyi3265/diaoxia-admin) | 管理后台 | 调用本服务完成门店、会员、预约、订单和运营管理 |
| [diaoxia-app](https://github.com/jiangyi3265/diaoxia-app) | 用户端 | 调用本服务完成用户浏览、预约、下单与会员相关功能 |

## 快速启动

1. 创建 MySQL 数据库，并导入 `sql/ry_20250522.sql` 与 `sql/xy_business.sql`。
2. 按本地环境修改 `ruoyi-admin/src/main/resources/application-druid.yml` 中的数据库与 Redis 配置。
3. 在项目根目录执行：

   ```bash
   mvn clean package -DskipTests
   java -jar ruoyi-admin/target/ruoyi-admin.jar
   ```

4. 启动 [diaoxia-admin](https://github.com/jiangyi3265/diaoxia-admin) 或 [diaoxia-app](https://github.com/jiangyi3265/diaoxia-app)，并将其 API 地址指向本服务。

## 简历描述示例

负责钓虾业务系统后端的设计与开发，基于 Spring Boot 构建统一认证和 REST API，覆盖门店、会员、预约、订单、商城等业务模块，并支撑管理后台与用户端协同运行。
