package com.robothy.s3.core.service;

import com.robothy.s3.core.model.answers.ListObjectsV2Ans;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.datatypes.response.S3Object;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ListObjectsV2ServiceTest extends LocalS3ServiceTestBase {

    @MethodSource("localS3Services")
    @ParameterizedTest
    void listObjectsV2FromEmptyBucket(BucketService bucketService, ObjectService objectService) {
        String bucket = "test-list-objects-v2";
        bucketService.createBucket(bucket);
        ListObjectsV2Ans listObjectsV2Ans = objectService.listObjectsV2(bucket, null, null, null, false, 100, null, null);
        assertNotNull(listObjectsV2Ans);
    }

    @MethodSource("localS3Services")
    @ParameterizedTest
    void listObjectsV2WithContinuationToken(BucketService bucketService, ObjectService objectService) {
        String bucket = prepareKeys(bucketService, objectService,
            "dir1/key1",
            "dir1/key2",
            "dir2/key1",
            "dir3#key1",
            "dir3#key2");
        ListObjectsV2Ans listObjectsV2Ans = objectService.listObjectsV2(bucket, null, null, null, false, 5, null, null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(5, listObjectsV2Ans.getObjects().size());
        assertEquals(0, listObjectsV2Ans.getCommonPrefixes().size());
        assertTrue(listObjectsV2Ans.getNextContinuationToken().isEmpty());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, null, null, null, false, 4, null, null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(4, listObjectsV2Ans.getObjects().size());
        assertEquals(0, listObjectsV2Ans.getCommonPrefixes().size());
        assertTrue(listObjectsV2Ans.getNextContinuationToken().isPresent());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, listObjectsV2Ans.getNextContinuationToken().get(), null, null, false, 4, null, null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(1, listObjectsV2Ans.getObjects().size());
        assertEquals(0, listObjectsV2Ans.getCommonPrefixes().size());
        assertFalse(listObjectsV2Ans.getNextContinuationToken().isPresent());
    }

    /**
     * The continuation token of a URL encoded listing continues after the last key of the page, whose URL encoding
     * sorts differently, e.g. {@code =} is encoded as {@code %3D}, which sorts before it. A client that follows the
     * tokens of a listing of Hive partitions, e.g. the glob of DuckDB, must get to the end of it.
     */
    @MethodSource("localS3Services")
    @ParameterizedTest
    void listObjectsV2WithEncodingTypeUrlFollowsTheTokensToTheEnd(BucketService bucketService,
                                                                  ObjectService objectService) {
        String bucket = prepareKeys(bucketService, objectService,
            "a-before-the-prefix.parquet",
            "events/part=0/data_0.parquet",
            "events/part=1/data_0.parquet",
            "events/part=2/data_0.parquet",
            "events/part=3/data_0.parquet",
            "events/part=4/data_0.parquet");

        List<String> keys = new java.util.ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            ListObjectsV2Ans page = objectService.listObjectsV2(bucket, token, null, "url", false, 2, "events/", null);
            page.getObjects().forEach(object -> keys.add(object.getKey()));
            token = page.getNextContinuationToken().orElse(null);
            assertTrue(++pages <= 3, "The listing must end after 3 pages, but got to page " + pages + ": " + keys);
        } while (token != null);

        assertEquals(List.of("events/part%3D0/data_0.parquet", "events/part%3D1/data_0.parquet",
            "events/part%3D2/data_0.parquet", "events/part%3D3/data_0.parquet", "events/part%3D4/data_0.parquet"), keys);
    }

    @MethodSource("localS3Services")
    @ParameterizedTest
    void listObjectsV2WithDelimiter(BucketService bucketService, ObjectService objectService) {
        String bucket = prepareKeys(bucketService, objectService,
                "key1",
                "dir1/key1",
                "dir1/key2",
                "dir1/folder1/key1",
                "dir1/folder2/key1",
                "dir2/a/b/c/key1");
        ListObjectsV2Ans listObjectsV2Ans = objectService.listObjectsV2(bucket, null, null, null, false, 10, null, null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(6, listObjectsV2Ans.getObjects().size());
        assertEquals(0, listObjectsV2Ans.getCommonPrefixes().size());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, null, null, null, false, 10, "dir1", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(4, listObjectsV2Ans.getObjects().size());
        assertEquals(0, listObjectsV2Ans.getCommonPrefixes().size());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, null, null, null, false, 10, "dir1/", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(4, listObjectsV2Ans.getObjects().size());
        assertEquals(0, listObjectsV2Ans.getCommonPrefixes().size());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, null, "/", null, false, 10, "dir1/", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(List.of("dir1/key1", "dir1/key2"), listObjectsV2Ans.getObjects().stream().map(S3Object::getKey).toList());
        assertEquals(List.of("dir1/folder1/", "dir1/folder2/"), listObjectsV2Ans.getCommonPrefixes());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, null, "/", null, false, 10, "dir1/folder1/", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(List.of("dir1/folder1/key1"), listObjectsV2Ans.getObjects().stream().map(S3Object::getKey).toList());
        assertEquals(0, listObjectsV2Ans.getCommonPrefixes().size());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, null, "/", null, false, 10, "dir2/", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(0, listObjectsV2Ans.getObjects().size());
        assertEquals(List.of("dir2/a/"), listObjectsV2Ans.getCommonPrefixes());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, null, "/", null, false, 10, "dir2/a", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(0, listObjectsV2Ans.getObjects().size());
        assertEquals(List.of("dir2/a/"), listObjectsV2Ans.getCommonPrefixes());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, null, "/", null, false, 10, "dir2/a/", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(0, listObjectsV2Ans.getObjects().size());
        assertEquals(List.of("dir2/a/b/"), listObjectsV2Ans.getCommonPrefixes());
    }

    @MethodSource("localS3Services")
    @ParameterizedTest
    void listObjectsV2WithDelimiterPaginationPageAtFile(BucketService bucketService, ObjectService objectService) {
        String bucket = prepareKeys(bucketService, objectService,
                "key1",
                "dir1/folder1/key1",
                "dir1/folder2/key1",
                "dir1/key1",
                "dir1/key2",
                "dir1/zoo",
                "dir2/a/b/c/key1");
        ListObjectsV2Ans listObjectsV2Ans = objectService.listObjectsV2(bucket, null, "/", null, false, 3, "dir1/", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(List.of("dir1/key1"), listObjectsV2Ans.getObjects().stream().map(S3Object::getKey).toList());
        assertEquals(List.of("dir1/folder1/", "dir1/folder2/"), listObjectsV2Ans.getCommonPrefixes());
        assertTrue(listObjectsV2Ans.getNextContinuationToken().isPresent());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, listObjectsV2Ans.getNextContinuationToken().get(),  "/", null, false, 10, "dir1/", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(List.of("dir1/key2", "dir1/zoo"), listObjectsV2Ans.getObjects().stream().map(S3Object::getKey).toList());
        assertEquals(0, listObjectsV2Ans.getCommonPrefixes().size());
        assertTrue(listObjectsV2Ans.getNextContinuationToken().isEmpty());
    }

    @MethodSource("localS3Services")
    @ParameterizedTest
    void listObjectsV2WithDelimiterPaginationPageAtCommonPrefix(BucketService bucketService, ObjectService objectService) {
        String bucket = prepareKeys(bucketService, objectService,
                "key1",
                "dir1/a",
                "dir1/folder1/key1",
                "dir1/folder2/key1",
                "dir1/folder2/key2",
                "dir1/folder3/key3",
                "dir1/z",
                "dir2/a/b/c/key1");
        ListObjectsV2Ans listObjectsV2Ans = objectService.listObjectsV2(bucket, null, "/", null, false, 3, "dir1/", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(List.of("dir1/a"), listObjectsV2Ans.getObjects().stream().map(S3Object::getKey).toList());
        assertEquals(List.of("dir1/folder1/", "dir1/folder2/"), listObjectsV2Ans.getCommonPrefixes());
        assertTrue(listObjectsV2Ans.getNextContinuationToken().isPresent());

        listObjectsV2Ans = objectService.listObjectsV2(bucket, listObjectsV2Ans.getNextContinuationToken().get(),  "/", null, false, 10, "dir1/", null);
        assertNotNull(listObjectsV2Ans);
        assertEquals(List.of("dir1/z"), listObjectsV2Ans.getObjects().stream().map(S3Object::getKey).toList());
        assertEquals(List.of("dir1/folder3/"), listObjectsV2Ans.getCommonPrefixes());
        assertTrue(listObjectsV2Ans.getNextContinuationToken().isEmpty());
    }

    /**
     * A page that ends at an object whose key begins other keys, e.g. {@code data.parquet} and {@code data.parquet.crc},
     * continues at the next key: the token carries the key of the object, not the last key that starts with it.
     */
    @MethodSource("localS3Services")
    @ParameterizedTest
    void listObjectsV2PageAtAKeyThatPrefixesOtherKeys(BucketService bucketService, ObjectService objectService) {
        String bucket = prepareKeys(bucketService, objectService,
                "data.parquet",
                "data.parquet.crc",
                "data.parquet\uFFFF",
                "logs");
        assertEquals(List.of("data.parquet", "data.parquet.crc", "data.parquet\uFFFF", "logs"),
            listAllKeys(objectService, bucket, null, null, 1));
    }

    /**
     * A page that ends at a common prefix continues after every key that the prefix rolls up, also the keys that continue
     * it with {@code Character.MAX_VALUE}.
     */
    @MethodSource("localS3Services")
    @ParameterizedTest
    void listObjectsV2PageAtACommonPrefixWithMaxCharacterKeys(BucketService bucketService, ObjectService objectService) {
        String bucket = prepareKeys(bucketService, objectService,
                "dir/a",
                "dir/\uFFFF",
                "dir/\uFFFFz",
                "dir0");
        assertEquals(List.of("dir/", "dir0"), listAllKeys(objectService, bucket, "/", null, 1));
    }

    /**
     * Keys are listed in the order of their UTF-8 bytes, as S3 lists them: U+FFFD (EF BF BD) before a supplementary
     * character such as U+1F600 (F0 9F 98 80), which UTF-16 order puts the other way around.
     */
    @MethodSource("localS3Services")
    @ParameterizedTest
    void listObjectsV2InUtf8Order(BucketService bucketService, ObjectService objectService) {
        String bucket = prepareKeys(bucketService, objectService,
                "a",
                "\uFFFD",
                "\uD83D\uDE00",
                "dir/\uFFFD",
                "dir/\uD83D\uDE00",
                "dir0");
        List<String> expected = List.of("a", "dir/\uFFFD", "dir/\uD83D\uDE00", "dir0", "\uFFFD", "\uD83D\uDE00");
        assertEquals(expected, listAllKeys(objectService, bucket, null, null, 1000));
        assertEquals(expected, listAllKeys(objectService, bucket, null, null, 1));
        assertEquals(List.of("a", "dir/", "dir0", "\uFFFD", "\uD83D\uDE00"), listAllKeys(objectService, bucket, "/", null, 1));
        ListObjectsV2Ans afterFffd = objectService.listObjectsV2(bucket, null, null, null, false, 1000, null, "\uFFFD");
        assertEquals(List.of("\uD83D\uDE00"), afterFffd.getObjects().stream().map(S3Object::getKey).toList());
    }

    /**
     * The keys and common prefixes of every page, following the continuation tokens to the end.
     */
    private static List<String> listAllKeys(ObjectService objectService, String bucket, String delimiter, String prefix,
                                            int maxKeys) {
        List<String> listed = new java.util.ArrayList<>();
        String token = null;
        for (int page = 0; page < 100; page++) {
            ListObjectsV2Ans ans = objectService.listObjectsV2(bucket, token, delimiter, null, false, maxKeys, prefix, null);
            ans.getObjects().forEach(object -> listed.add(object.getKey()));
            listed.addAll(ans.getCommonPrefixes());
            if (ans.getNextContinuationToken().isEmpty()) {
                return listed;
            }
            token = ans.getNextContinuationToken().get();
        }
        throw new AssertionError("The listing never ended: " + listed);
    }

    String prepareKeys(BucketService bucketService, ObjectService objectService, String... keys) {
        String bucket = "test-list-objects-v2" + UUID.randomUUID();
        bucketService.createBucket(bucket);
        for (String key : keys) {
            objectService.putObject(bucket, key, PutObjectOptions.builder()
                .content(new ByteArrayInputStream("test".getBytes()))
                .size(4L)
                .build());
        }
        return bucket;
    }

}