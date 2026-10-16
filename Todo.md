

### virtual host 支持

`*.127-0-0-1.nip.io` 可以将任何虚拟主机风格请求映射到 127.0.0.1，让虚拟主机风格请求零配置可用。 
默认virtual host域名添加 `127-0-0-1.nip.io` 支持。

同时更新文档。


### 2.2 请求验证与断言 API — P1

基于 AssertJ 风格，在 local-s3-jupiter module 中添加断言支持，方便对 S3Client和 LocalS3 进行测试。

```java
assertThat(s3Client).bucket("uploads").hasObject("a.txt")
    .withContentType("text/plain").withMetadata("owner", "alice").withTag("env", "test");
```
