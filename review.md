# LocalS3 代码 Review（2026-11-24）

基线：`main` @ `fae62efe`。

## 范围与方法

- **静态阅读**：核心写路径与并发，包括 `PutObjectService`（含 append）、`GetObjectService`、`DeleteObjectService`、
  `CompleteMultipartUploadService`、`ListObjectsV2Service` / `ListItemUtils`、`Range`、`RangeUtils`、
  `DefaultBucketGuard` / `ServiceLock` / `DefaultBucketLock`、`InMemoryStorage`、`AwsSignatureV4Verifier`（规范请求、
  时间校验、chunk 签名）、`LocalS3Router` 的 bucket/key 解析、`NettyServer` 停机流程，以及 Admin / Console / MCP /
  Restore 中各处数字解析。
- **黑盒探测**：启动 `local-s3-standalone` fat jar（IN_MEMORY、PERSISTENCE、开启签名、开启 Iceberg catalog 四种配置），
  用 boto3 / curl / 原始 socket 跑了约 150 个边界场景：
  - 版本控制：Enabled → Suspended → Enabled，null version，delete marker
  - Range：后缀、越界、多段、空对象
  - 多段上传：重试 Complete、按 `partNumber` 读、`UploadPartCopy`
  - 带分隔符的 V1/V2 分页，以及版本列表分页
  - 特殊字符 key、条件写的并发竞争（`If-None-Match: *` / `If-Match`）
  - Object Lock：GOVERNANCE / COMPLIANCE / Legal Hold / 默认保留
  - 校验和：MD5 / CRC32 / CRC64NVME
  - SigV4 / SigV2 / 预签名 URL / POST Policy / 虚拟主机
  - 持久化模式下重启后的数据一致性，以及并发覆盖写同时读取（检查撕裂读和孤儿文件）
  - Iceberg REST 的提交冲突，以及 namespace 级联规则
- **全量测试**：`./gradlew test --offline --continue`，结果见第 4 节。

总体结论：核心 S3 语义、并发控制和持久化质量很高，绝大多数边界场景与 AWS 一致，并发竞争与重启一致性都没有发现问题。
确认的问题集中在下面几条。

---

## 1. 确认的缺陷

### 1.1 【中】以 `/` 开头的对象 key 被悄悄去掉前导斜杠，与不带斜杠的 key 指向同一个对象

> **状态：已修复（2026-11-24）**
>
> - 文档：`docs/semantics.md#request-validation` 新增一条，写明 key 按请求原样保存，`/a` 与 `a` 是两个对象；
>   `CHANGELOG.md` 的 2.5.0 `Fixed` 中说明这是行为变化。
> - 代码：去掉 `RequestAssertions.assertObjectKeyProvided`、`SessionPolicyAuthorizer`、`Faults` 三处对 key 前导 `/`
>   的剥离。`CopySource` 与 `x-amz-rename-source` 剥掉的是 `/bucket/key` 格式里 bucket 前的 `/`，并不触及 key 本身
>   （`/bucket//a` 解析出的 key 仍是 `/a`），无需修改。会话策略的资源因此变为 `arn:aws:s3:::bucket//a`，与 AWS 一致。
> - 测试：新增 `LeadingSlashKeyIntegrationTest`，覆盖 SDK 写入 `/lead` 与 `lead` 互不覆盖、列表返回 `/lead`、
>   以 `/lead` 为源的 CopyObject、删除，以及原始 HTTP `PUT /bucket//lead` 后 `GET /bucket/lead` 为 404。
>   修复前 2 个用例均失败，修复后通过；对象、复制、重命名、列表、虚拟主机、STS 相关集成测试均通过。
> - 已有持久化数据：旧版本写入的 `/a` 在数据目录中存为 `a`，升级后仍以 `a` 可读，不做迁移（已在 CHANGELOG 说明）。

- 位置：`local-s3-rest/.../assertions/RequestAssertions.java:38-42`（`assertObjectKeyProvided`）
  ```java
  return request.parameter("key")
      .map(key -> key.startsWith("/") ? key.substring(1) : key)
  ```
- 现象（已复现）：
  ```
  PUT /probe2//lead        -> 200
  GET /probe2/lead         -> 200   （同一个对象）
  ListObjectsV2            -> Key = "lead"（AWS 返回 "/lead"）
  ```
  `LocalS3Router.matchPath` 已经正确地把 `/bucket//lead` 解析为 key `"/lead"`，虚拟主机风格同理。前导 `/` 是
  `assertObjectKeyProvided` 再剥掉的。这段逻辑来自最早期的提交（`36e2504a`），文档里没有说明。
- 影响：
  - AWS 中 `"/a"` 和 `"a"` 是两个不同的对象，这里会互相覆盖，属于静默的数据冲突。
  - 以 `/` 开头的 key 在列表里显示的名字和写入时不同，客户端做"写入后按列表校验"会失败。
  - `CopySource` / `x-amz-rename-source` / `SessionPolicyAuthorizer` / `Faults` 也各自剥掉一个前导 `/`。只修
    `assertObjectKeyProvided` 会造成不一致，需要一起梳理。
- 建议：路由已经给出精确的 key，`assertObjectKeyProvided` 不应再改写它。先在 `docs/semantics.md` 写明 key 原样保存，
  再改代码，并补上 `//key` 的集成测试。另外要评估已有持久化数据里是否有依赖这一行为的 key，并在 CHANGELOG 中说明
  这是行为变化。

### 1.2 【中】`GracefulShutdownTest.aResponseBeingWrittenIsWrittenBeforeTheServiceStops` 稳定失败

> **状态：已修复（2026-11-24）。根因在测试，服务端优雅停机没有问题。**
>
> - 根因：用例把客户端接收缓冲设为 4 KB，小于 loopback 的 MSS（32–64 KB）。发送端因此进入零窗口探测（persist
>   定时器），每次探测只发几 KB，16 MiB 根本不可能在停机的 5 秒超时内传完。停住的约 2.5 MB 是已经交给内核、
>   积压在服务端 socket Send-Q 里的数据，Netty 这一侧并没有停止传输。
> - 证据：用探针测试在**不停机**的情况下（只读、不调 `shutdown()`）同样复现。`ss -tnoi` 显示服务端连接
>   `timer:(persist)`、`rwnd_limited:100%`、`snd_wnd:7936`、`notsent:2523136`，100 多秒只送达约 4.4 MB；
>   同一时刻 event loop 线程都空闲在 `epoll_wait`。上文"对照实验能完整收到"的结论不成立，排查方向中的
>   `ChunkedWriteHandler` / `InFlightRequests` / `IdleConnectionHandler` 都不是原因。
> - 修复：只改测试，不改服务端代码。不再缩小接收缓冲，改为客户端在停机开始前不读取，让响应积压在服务端；对象
>   加大到 32 MiB，超过两端 socket 缓冲的自动调优上限（默认 wmem 4 MiB + rmem 6 MiB），保证停机时响应仍在写。
> - 验证：`GracefulShutdownTest` 连跑 3 次全部通过，该用例耗时从 65 秒降到约 1.3 秒。变异检查：临时去掉
>   `NettyServer.stop()` 里的 `awaitResponsesInFlight`，用例按预期失败（"Shutting down waits for the response
>   being written"），说明测试仍能发现真正的回归。
> - CI：新写法不依赖小于 MSS 的接收窗口，对内核 socket 缓冲行为的依赖只剩"缓冲上限小于 32 MiB"，可以预期在 CI
>   上同样稳定。

- 用 `./gradlew :local-s3-rest:test --tests '*GracefulShutdownTest'` 连跑 3 次，3 次都失败：
  ```
  array lengths differ, expected: <16777216> but was: <2588278>
  WARN ... 1 responses were still being written after 5 seconds.
  ```
- 场景：客户端接收缓冲设为 4 KB，开始读一个 16 MiB 的 in-memory 对象，然后调用 `shutdown()`。响应在约 2.5 MB 处停住，
  直到 `awaitResponsesInFlight` 的 5 秒超时，event loop 被关掉，响应被截断。单个用例耗时 65 秒。
- 对照实验：在不停机的服务上，用同样小的接收缓冲、延迟 0 / 0.3 / 2 秒再读，16 MiB 都能完整收到。所以问题只出现在
  停机过程中。可以判断：`closeServerSocket()` 或 `executor.shutdown()` 之后，`ChunkedWriteHandler` 在连接重新可写时
  没有再恢复传输，至少在当前环境（Linux 6.8 / Netty 当前版本）下是这样。
- 影响：嵌入式场景（Spring Boot DevTools 重启、测试结束时停服务）下，正在下载的大对象会被截断。客户端只会看到连接被
  关闭，而这正是"优雅停机"承诺要避免的。
- 根因尚未定位。建议排查方向：
  - 停机时 `channelWritabilityChanged` 是否仍然送达 `ChunkedWriteHandler`。
  - `InFlightRequests` 计数的结束点是否早于实际写完。
  - `IdleConnectionHandler` 在停机期间的行为。
- 注意：这条测试在 CI 上是否也失败需要确认。如果 CI 上通过，说明它依赖内核 socket 缓冲区的行为，测试本身也需要调整。

### 1.3 【低】版本化 bucket 与非版本化 bucket 对非法 versionId 的 `DeleteObject` 行为不一致，且与文档不符

> **状态：已修复（2026-11-24）。统一为文档描述的"幂等成功、什么都不删"。**
>
> - 取舍：没有改成两种 bucket 都返回 400。文档的幂等语义是有意设计的（Iceberg / Delta Lake / DuckLake 的重试和并发
>   清理不应失败），版本化 bucket 和 `DeleteObjects` 已经这样实现；只有从未版本化的 bucket 是例外，改它影响面最小。
> - 文档：`docs/semantics.md#versioning` 明确写出"从未版本化的 bucket 中，非 `null` 的 versionId 也不报错、什么都不删"，
>   `DeleteObjects` 的每个 key 同样如此。
> - 代码：`DeleteObjectService.deleteObject` 在从未版本化的 bucket 遇到非 `null` versionId 时直接返回空结果，不删除对象，
>   也不发布 `S3Change`（与版本化 bucket 中"版本已不存在"的分支一致）；`deleteObjectFromUnVersionedBucket` 去掉了
>   抛异常的首行（生命周期调用它时只传 `null`）。注意不能只删掉那行 `throw`，否则 `versionId=bogus` 会把当前对象删掉。
> - 实测补充：原表格中"`DeleteObjects` 计入 `Deleted`"只对版本化 bucket 成立；从未版本化的 bucket 原来返回
>   `InvalidArgument` 错误项，现在同样计入 `Deleted`。
> - 测试：更新 `DeleteObjectServiceTest`、`DeleteObjectsServiceTest`、`VersionIdArgumentTest`、
>   `RejectedChangeLeavesMetadataUnchangedTest`（去掉不再成立的断言），新增集成测试
>   `VersionIdHeaderIntegrationTest.deleteWithAVersionIdOtherThanNullDeletesNothingInANeverVersionedBucket`
>   （`bogus` / `123` 均返回 204，对象仍在）。core、rest、相关集成测试通过；失败的只有第 4 节已知的环境用例
>   （`LocalFileSystemStorageTest` 一例、`NativeImageMetadataTest` 三例）。

- `docs/semantics.md` 的版本控制一节写道："Unlike a read, a delete doesn't fail on a malformed version ID either;
  it deletes nothing."
- 实测：

  | 场景 | 结果 |
  |---|---|
  | 从未开启版本控制的 bucket：`DELETE /b/k?versionId=bogus` | `400 InvalidArgument` |
  | 版本控制已开启的 bucket：`DELETE /b/k?versionId=bogus` | `204` |
  | `DeleteObjects` 中带 `VersionId=bogus` | 计入 `Deleted` |

- 位置：`DeleteObjectService.deleteObjectFromUnVersionedBucket` 首行的 `throw VersionedObjectAssertions.invalidVersionId(...)`。
- 建议：统一成文档描述的行为（幂等成功），或者修改文档，明确"从未版本化的 bucket 例外"。AWS 对格式错误的 versionId
  一般返回 400，按"兼容核心 API"的原则，也可以考虑反过来让两种 bucket 都返回 400。不管选哪种，都需要先改文档。

---

## 2. 潜在风险 / 需要确认的设计点

1. **`DefaultBucketLock` 按条带共享读写锁，与"读锁不可升级"的约束叠加后有死锁风险。**
   64 个 bucket 共用 64 把 `ReentrantReadWriteLock`。如果某个线程持有 bucket A 的读锁，再去获取 bucket B 的写锁，而 A
   和 B 恰好哈希到同一条带，就等于在同一把锁上做读升级写，线程会永久阻塞。`BucketGuard` 的 Javadoc 只约束了"同一个
   bucket"不能读升级写。目前审阅到的跨 bucket 操作（Copy / UploadPartCopy / Append）都是先在读锁下取出源，释放后再
   获取写锁，所以是安全的。建议：
   - 在 `BucketGuard` 的 Javadoc 中把约束扩展为"持有任何 bucket 的读锁时，不得获取任何 bucket 的写锁"。
   - 或者在 `DefaultBucketGuard.change` 中检测"当前线程持有任意读锁"并直接抛异常，让这个约束在测试中暴露出来。

   > **状态：已修复（2026-11-24），采用第一种方案。** `BucketGuard` 的 Javadoc 已改为"持有任何 bucket 的读锁时，不得对任何
   > bucket 调用 `write` / `change`"，并说明了跨 bucket 操作应"先读源、释放读锁、再改目标"；`DefaultBucketLock` 中"一个操作只锁
   > 一个 bucket，所以安全"的说法也同步修正。未加运行时断言：`ReentrantReadWriteLock` 无法廉价地查询"当前线程是否持有任意条带的
   > 读锁"，需要额外的 ThreadLocal 计数，目前没有违反约束的调用点，收益不大。
2. **SigV4 重复请求头的规范化。** `normalizedHeaders` 用 `HashMap.put` 收集请求头。如果同名请求头出现多次，后者会覆盖
   前者，而 SigV4 规范要求用逗号把多个值拼接起来。签名的客户端很少发送重复的签名头，所以只是低概率误报
   `SignatureDoesNotMatch`。不过要先确认 `RouterHttpRequest.getHeaders()` 是否已经做过拼接。

   > **状态：已确认无问题（2026-11-24），无需修改代码。** 拼接已在上游完成：`RouterHttpRequestDecoder` 把每个值 trim 后，
   > 按接收顺序用逗号合并同名请求头（名称先转小写）；`RouterHttpRequest` 的构造器对仅大小写不同的名称同样用逗号 `merge`。
   > 所以 `getHeaders()` 中每个名称只有一项，`normalizedHeaders` 的 `put` 不会覆盖任何值，再折叠连续空白后与 SigV4
   > “各值 trim、逗号拼接”的规范结果一致。已有测试覆盖：`RouterRouterHttpRequestDecoderTest`（`"a,b c"`）和
   > `AwsSignatureV4VerifierTest.verifiesRepeatedHeadersAndUnnecessarilyEncodedCharacters`（重复的 `x-amz-meta-tag`
   > 通过验签），两者均已重新运行并通过。唯一的偏差是 `Cookie` 按 RFC 6265 用 `"; "` 拼接，而不是用逗号，只有把重复的
   > `Cookie` 列为签名头时才会受影响。S3 客户端通常不签名 `Cookie`，所以不做处理。
3. **IN_MEMORY 模式下操作失败时的回滚。** `DefaultBucketGuard` 在没有 `bucketMetaStore` 时只加锁，没有元数据回滚和
   延迟删除。`PutObjectService.addVersion` 在替换 null version 时会立即删除旧内容。如果后续步骤抛出非
   `LocalS3Exception` 的异常，内存元数据会停在中间状态：旧内容已删除，新版本可能已经挂上。目前的调用链里
   `addVersion` 之后只有 `publishChange`（只入队，不投递），所以没有实际触发点。如果以后在 `addVersion` 之后加入新的
   校验，就需要注意这一点。

   > **状态：已修复（2026-11-24），采用内存撤销方案。** 修正一处描述：IN_MEMORY 模式其实有 `bucketMetaStore`
   > （`HeapBucketMetadataStore`），缺的是存储事务和回滚手段，所以走的是持久化分支，只是失败时无事可做。只做延迟删除不够：
   > `putObject` 失败后，`discardStoredContent` 会立即删除新内容，而半途改过的元数据可能已经引用了它。修改如下：
   > - 内存模式的存储也包上 `TransactionalStorage`。被替换的内容要等变更成功后才删除，失败时删除本次写入的内容。
   > - 新增 `ChangeUndo`，由 `DefaultBucketGuard` 在写锁内调用。`BucketMetadata.recordUndo()` 先记下 bucket 的设置，
   >   再在每个对象 key（`getObjectMetadata` / `putObjectMetadata` / `removeObjectMetadata`）和每个 key 的 uploads
   >   （`markUploadsChanged`）第一次被修改前记录原状态。任何失败（包括拒绝类异常）都按记录恢复；新建的 bucket 会被移除，
   >   被删除的 bucket 会被放回。为此，5 处修改 uploads 的服务都把 `markUploadsChanged` 移到了修改之前。
   > - `change` 整体放进 `serviceLock.shared`，这样事务的 begin 和 commit 之间不会插入 `reset()`（`reset()` 会替换存储）。
   >   事件仍在锁外投递。
   > - 代价：每次变更都会深拷贝所涉 key 的元数据，开销随该 key 的版本数增长；uploads 只按 key 浅拷贝 parts 映射。
   >   文档见 `docs/architecture.md#a-change`。
   > - 测试：`InMemoryChangeUndoTest`（PUT 替换 null version、UploadPart 替换 part 时注入失败；撤掉修复后两条都失败）
   >   和 `BucketMetadataTest` 中的两条 undo 用例。
   > - 未覆盖：IN_MEMORY 的 S3 Vectors（`BucketGuard.inMemory()`）仍然不回滚。
4. **`ListObjectsV2` 在 `max-keys=0` 时返回 `IsTruncated=false`。** 和 AWS 当前行为一致，这里只做记录，不需要修改。
5. **生命周期规则匹配的对象没有返回 `x-amz-expiration`。** 实测在有 `Expiration.Days` 规则的 bucket 中，PUT / HEAD
   都不返回该响应头。依赖这个头的客户端（少数备份工具和 SDK 示例）拿不到过期时间。如果文档声明支持 Lifecycle，
   建议补上，或者在文档中注明不返回。

   > **状态：已修复（2026-11-24），已补上该响应头。** 行为写在 `docs/semantics.md#lifecycle-configuration`。
   > - 新增 `ObjectExpiration.of(bucket, key, version)`：在已启用、带 `Expiration.Days` / `Date` 的规则中，按 key、标签、
   >   大小（与执行生命周期时同一个 `Filter.matches`）选出规则，取最早的过期时间。`Days` 沿用 `LifecycleRule.dueAt`
   >   （向上取整到 UTC 零点），与实际执行一致。
   > - 格式与 AWS 相同：`expiry-date="Fri, 02 Jan 2026 00:00:00 GMT", rule-id="..."`，`rule-id` 做 URL 编码。
   > - 返回该头的操作：PUT（在 `addVersion` 中计算）、CopyObject（目标对象）、CompleteMultipartUpload（含重试时的幂等应答），
   >   以及当前版本的 GET / HEAD。非当前版本、delete marker、`304` 不返回。
   > - 测试：`LifecycleExecutionIntegrationTest.answersTheExpirationOfAnObjectThatARuleExpires`，覆盖多规则取最早、
   >   rule-id 编码、禁用规则、非当前版本，以及 copy / multipart / 无规则 bucket。core、rest、integration 全部测试已运行，
   >   只剩第 4 节列出的环境类失败。
   > - 代价：有生命周期配置的 bucket，每次读写都会解析一次配置 XML。规则数通常很少，所以没有加缓存。
   > - 与 AWS 可能的差异：带标签过滤的规则也会返回该头（与 LocalS3 实际执行的结果一致）；AWS 对这种情况的行为没有在
   >   文档中写明。

---

## 3. 已验证无问题的部分（供后续 review 跳过）

| 领域 | 结论 |
|---|---|
| 版本控制状态机 | Suspended 下 null version 的替换和删除、delete marker 的 404/405 与 `x-amz-delete-marker` 都正确 |
| Range | 后缀、越界截断、`-0`、多段（忽略）、逆序（忽略）、空对象 416 都与 AWS 一致 |
| 多段上传 | 重试 Complete 时幂等返回同一版本；按 `partNumber` 读时 Content-Range 和 PartsCount 正确；`fileId` 是原始类型 `long`，`!=` 比较正确 |
| 列表分页 | 带分隔符的 V1/V2 分页、版本分页不重不漏；`StartAfter` 按 UTF-8 字节序；`EncodingType=url` 正确 |
| 条件写竞争 | 40 个线程并发 `If-None-Match: *` 和 `If-Match`，各只有 1 个成功（真正的 CAS） |
| 并发覆盖写同时读 | 4 个写线程、4 个读线程，读取到的数据没有撕裂，PERSISTENCE 模式下没有残留孤儿内容文件 |
| 持久化 | 重启后对象、元数据、标签、版本链、未完成的上传与分片、bucket 标签都完整保留 |
| Object Lock | GOVERNANCE 的 bypass、COMPLIANCE 不可缩短或降级、Legal Hold、默认保留（含多段上传） |
| 签名 | SigV4 对特殊字符 key 和查询参数都正确；预签名 GET/PUT 和篡改检测；POST Policy 的 `content-length-range`；虚拟主机；SigV2 兼容 |
| 校验和 | Content-MD5 与 CRC32 不匹配时返回 BadDigest；Range 读取时不返回整对象校验和 |
| 数字解析 | Admin / Console / MCP / Restore / chunk 长度等各处都处理了 `NumberFormatException` 和负数 |
| Iceberg REST | requirement 冲突返回 409；非空 namespace 和带子 namespace 的 namespace 拒绝删除；重命名到不存在的 namespace 返回 404 |

---

## 4. 测试套件结果（`./gradlew test --offline --continue`）

| 失败项 | 判断 |
|---|---|
| `GracefulShutdownTest.aResponseBeingWrittenIsWrittenBeforeTheServiceStops` | ~~真实问题~~ 测试本身的问题（接收缓冲小于 MSS），已修复，见 1.2 |
| `LocalFileSystemStorageTest.aRenamedFileStaysStoredWhenTheFileOfTheFlatLayoutCannotBeDeleted` | 环境原因：以 root 身份运行，去掉目录权限也挡不住删除。建议在 root 下用 `assumeFalse` 跳过 |
| `NativeImageMetadataTest`（3 个用例） | 环境原因：`reachability-metadata.json` 由构建任务生成，单跑 `test` 时不在 classpath。建议让 `test` 依赖生成任务，或者在缺文件时 `assume` 跳过 |
| `S3ExpressIntegrationTest`（多个用例） | 环境原因：沙箱内无法解析 S3 Express 的虚拟主机域名（`UnknownHostException`） |
| testcontainers / spring-boot-starter / standalone 的 `compileTestJava` | 环境原因：离线模式下缺少测试依赖 |

建议：后两类环境原因的失败，最好在缺少条件时显式 `assume` 跳过，而不是报错，这样能减少本地误报，避免盖住真实的失败。
（1.2 原本也被当作真实失败，后来确认是测试依赖了内核 TCP 行为。）

---

## 5. 建议的处理顺序

1. ~~1.1：去掉 key 前导 `/` 的剥离逻辑（先改文档，再改代码，并补集成测试）。~~ 已修复。
2. ~~1.2：定位优雅停机时响应传输停住的根因。~~ 已修复（测试问题）。
3. ~~1.3：统一对非法 versionId 的删除语义，使文档与代码一致。~~ 已修复。
4. ~~第 2 节第 1 条：把锁约束写进 `BucketGuard` 的 Javadoc，或者加运行时断言。~~ 已修复（Javadoc）。
5. 第 4 节：让依赖环境的测试在条件不满足时跳过。
6. ~~第 2 节第 5 条：为生命周期规则匹配的对象返回 `x-amz-expiration`。~~ 已修复。
