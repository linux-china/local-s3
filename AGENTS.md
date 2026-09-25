# 项目概览

LocalS3 是一个基于 Netty 的 Amazon S3 服务模拟实现，面向 **测试与本地开发**：启动快、依赖轻，
支持内存模式与持久化模式，同时实现了 S3 Vectors API。

核心特性：

- S3 API 兼容性：兼容核心 API，对一些未必要真的实现，如加密等，要做到让客户端的逻辑能跑通，不影响正常的功能
- Iceberg REST Catalog: 方便 Iceberg 对接
- S3 Tables API: AWS 托管 Iceberg 的入口，每个 table bucket 同时以 Iceberg REST catalog 形式提供（`s3tables` 签名作用域路由，未签名客户端走 `/s3tables` 前缀）
- 内置 Spring Boot Starter 支持：local-s3-spring-boot-starter，请忽略 Spring Boot 3 和 Netty 4.2的兼容问题，Spring Boot 能够保证兼容。

主要使用场景：

- Java 应用内嵌 S3 Server 支持：如Spring Boot应用可以内嵌 S3 Server，其他应用可以向其发送文件存储请求并进行通知
- JetBrains IDE：IDE内嵌 S3 Server，S3 Server 伴随着 IDE 启动，可以为数据分析场景提供默认 S3 服务，如 DuckDB 等
- 为大数据平台提供快速本地测试：如 Iceberg，Delta Lake, DuckLake 等，可快速接入 S3 服务进行测试
- 为 AI Agent 提供 artifacts 存储能力，保存各种文件，中间产物等

## 项目模块

| 模块                           | 职责                                                                            | 
|--------------------------------|---------------------------------------------------------------------------------|
| `local-s3-datatypes`           | S3 / S3 Vectors 请求响应模型（Jackson XML/JSON）                                | 
| `local-s3-core`                | 元数据模型、服务（接口 default 方法实现）、存储、锁、持久化、向量检索、Iceberg/S3 Tables 目录、事件触发 | 
| `local-s3-rest`                | Netty HTTP 服务、路由、Controller、SigV4 校验、CORS、虚拟主机                   | 
| `local-s3-jupiter`             | JUnit 5 扩展 `@LocalS3`，注入 `S3Client`/`S3VectorsClient`                      |
| `local-s3-testcontainers`      | `LocalS3Container`，方便对接 TestContainers                                     |
| `local-s3-standalone`          | 可执行 fat jar + Docker（JVM / GraalVM native）                                 |    
| `local-s3-spring-boot-starter` | Local S3 starter for Spring Boot 3/4 app                                        |    
| `local-s3-integration-test`    | 基于 AWS SDK v2 的端到端测试                                                    |    

## 技术栈

- Gradle 9.7
- Java 21
- Netty 4.2: netty-http-router
- Jackson 3.x（`tools.jackson`；注解仍是 `com.fasterxml.jackson.annotation`；mapper 使用 Jackson 2 默认配置构建）
- Lombok
- H2 MVStore: a persistent, log structured key-value store
- s5cmd: S3 and local filesystem execution tool

## 安全考量

考量到 Local S3主要用于本地开发测试，并不需要严格的安全验证，如果太严格，可能会导致开发者使用起来比较麻烦，
所以安全方面只需要告知开发者这些潜在的风险，由开发者自行考量。

