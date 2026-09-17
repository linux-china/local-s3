

###


| **支持 Flexible Checksums**（`x-amz-checksum-crc32/crc32c/crc64nvme/sha1/sha256`） | AWS SDK Java v2 从 2.30 起、boto3 从 1.36 起默认发送 CRC 校验和。现在 LocalS3 不校验、不保存，`GetObjectAttributes` 也不返回 `Checksum`。建议写入时校验并存到 `VersionedObjectMetadata`，在 `HeadObject` / `GetObject`（`x-amz-checksum-mode: ENABLED`）/ `GetObjectAttributes` / `CompleteMultipartUpload` 中返回，这样才能测试依赖端到端完整性的代码。 |
