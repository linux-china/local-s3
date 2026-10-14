

### 内置 MCP Server

 在 `/_mcp` 上实现 Streamable HTTP 的 JSON-RPC，**不引入新依赖**。
 暴露的工具包括 `list_buckets`、`list_objects`、`read_object`（文本、Range、大小上限）、`put_object`、`presign`、`delete_object`、`query_vectors`；
 对象以 `s3://` resource 的形式提供。Claude Code、IDE Agent 等可以直接把 LocalS3 当成产物仓库，与控制台的"给人看"形成互补。
 鉴权沿用控制台的 Basic 或单 key 方案，作为可选特性默认关闭，与 `features.*` 一致 

