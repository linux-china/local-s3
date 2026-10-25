package com.robothy.s3.core.service.s3vectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import com.robothy.s3.datatypes.s3vectors.ListOutputVector;
import com.robothy.s3.datatypes.s3vectors.response.ListVectorsResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ListVectorsServiceTest {

  @Test
  void listVectors_withValidRequest_returnsVectorList() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", createVectorMetadata("vector1", 1L));
    vectorObjects.put("vector2", createVectorMetadata("vector2", 2L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", null, null, false, false, null, null);
    
    assertNotNull(response);
    assertNotNull(response.vectors());
    assertEquals(2, response.vectors().size());
    assertEquals("vector1", response.vectors().get(0).getKey());
    assertEquals("vector2", response.vectors().get(1).getKey());
  }

  @Test
  void listVectors_withNonExistentBucket_throwsException() {
    TestListVectorsService service = new TestListVectorsService();
    
    assertThrows(LocalS3VectorException.class, 
        () -> service.listVectors("nonexistent", "index", null, null, false, false, null, null));
  }

  @Test
  void listVectors_withNonExistentIndex_throwsException() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    service.bucketMap.put("bucket", bucketMetadata);
    
    assertThrows(LocalS3VectorException.class,
        () -> service.listVectors("bucket", "nonexistent", null, null, false, false, null, null));
  }

  @Test
  void listVectors_withMaxResults_limitsResults() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    for (int i = 1; i <= 5; i++) {
      vectorObjects.put("vector" + i, createVectorMetadata("vector" + i, (long) i));
    }
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", 3, null, false, false, null, null);
    
    assertEquals(3, response.vectors().size());
  }

  @Test
  void listVectors_withMaxResultsExceedingLimit_capsAt1000() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", createVectorMetadata("vector1", 1L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", 2000, null, false, false, null, null);
    
    // Should still return all available vectors (1) despite high maxResults
    assertEquals(1, response.vectors().size());
  }

  @Test
  void listVectors_withNullMaxResults_usesDefault500() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", createVectorMetadata("vector1", 1L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", null, null, false, false, null, null);
    
    assertNotNull(response);
    assertEquals(1, response.vectors().size());
  }

  @Test
  void listVectors_withNextToken_startsFromCorrectPosition() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    for (int i = 1; i <= 5; i++) {
      vectorObjects.put("vector" + i, createVectorMetadata("vector" + i, (long) i));
    }
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    String nextToken = service.listVectors("bucket", "index", 2, null, false, false, null, null).nextToken();
    ListVectorsResponse response = service.listVectors("bucket", "index", 2, nextToken, false, false, null, null);
    
    assertEquals(2, response.vectors().size());
    assertEquals("vector3", response.vectors().get(0).getKey());
    assertEquals("vector4", response.vectors().get(1).getKey());
  }

  @Test
  void listVectors_withInvalidNextToken_throwsException() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    for (String token : new String[] {"invalid_token", Base64.getEncoder().encodeToString("-5".getBytes()),
        Base64.getEncoder().encodeToString("2".getBytes())}) {
      LocalS3VectorException e = assertThrows(LocalS3VectorException.class,
          () -> service.listVectors("bucket", "index", null, token, false, false, null, null));
      assertEquals(LocalS3VectorErrorType.VALIDATION, e.getErrorType());
    }
  }

  @Test
  void listVectors_continuesAfterTheLastKey_whenVectorsChangeBetweenPages() {
    TestListVectorsService service = new TestListVectorsService();
    VectorIndexMetadata indexMetadata = indexWithVectors(service, "b", "d", "f", "h");

    ListVectorsResponse first = service.listVectors("bucket", "index", 2, null, false, false, null, null);
    assertEquals(List.of("b", "d"), keys(first));

    indexMetadata.getVectorObjects().put("a", createVectorMetadata("a", 10L));
    indexMetadata.getVectorObjects().remove("b");
    indexMetadata.getVectorObjects().put("e", createVectorMetadata("e", 11L));

    ListVectorsResponse second = service.listVectors("bucket", "index", 2, first.nextToken(), false, false, null, null);
    assertEquals(List.of("e", "f"), keys(second));
    ListVectorsResponse third = service.listVectors("bucket", "index", 2, second.nextToken(), false, false, null, null);
    assertEquals(List.of("h"), keys(third));
    assertNull(third.nextToken());
  }

  @Test
  void listVectors_continuesAfterTheLastKey_whenTheLastKeyIsDeleted() {
    TestListVectorsService service = new TestListVectorsService();
    VectorIndexMetadata indexMetadata = indexWithVectors(service, "a", "b", "c");

    ListVectorsResponse first = service.listVectors("bucket", "index", 2, null, false, false, null, null);
    indexMetadata.getVectorObjects().remove("b");

    ListVectorsResponse second = service.listVectors("bucket", "index", 2, first.nextToken(), false, false, null, null);
    assertEquals(List.of("c"), keys(second));
  }

  @Test
  void listVectors_segmentsListEveryVectorOnce_andKeepTheirVectorsWhenOthersChange() {
    TestListVectorsService service = new TestListVectorsService();
    String[] ids = new String[50];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = "vector" + i;
    }
    VectorIndexMetadata indexMetadata = indexWithVectors(service, ids);

    List<List<String>> segments = listSegments(service, 4);
    List<String> all = segments.stream().flatMap(List::stream).sorted().toList();
    assertEquals(Arrays.stream(ids).sorted().toList(), all);

    for (int i = 0; i < 20; i++) {
      indexMetadata.getVectorObjects().put("other" + i, createVectorMetadata("other" + i, 100L + i));
    }
    indexMetadata.getVectorObjects().remove("vector0");
    List<List<String>> changed = listSegments(service, 4);
    for (int segment = 0; segment < 4; segment++) {
      List<String> before = new ArrayList<>(segments.get(segment));
      before.remove("vector0");
      List<String> after = changed.get(segment).stream().filter(id -> id.startsWith("vector")).toList();
      assertEquals(before, after);
    }
  }

  @Test
  void listVectors_withEmptyNextToken_startsFromBeginning() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", createVectorMetadata("vector1", 1L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", null, "", false, false, null, null);
    
    assertEquals(1, response.vectors().size());
    assertEquals("vector1", response.vectors().get(0).getKey());
  }

  @Test
  void listVectors_withReturnDataTrue_includesVectorData() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", createVectorMetadata("vector1", 1L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    when(service.mockVectorStorage.getVectorData(1L)).thenReturn(new float[]{1.0f, 2.0f});
    
    ListVectorsResponse response = service.listVectors("bucket", "index", null, null, true, false, null, null);
    
    assertEquals(1, response.vectors().size());
    ListOutputVector vector = response.vectors().get(0);
    assertNotNull(vector.getData());
    assertArrayEquals(new float[]{1.0f, 2.0f}, vector.getData().getValues());
  }

  @Test
  void listVectors_withReturnDataFalse_excludesVectorData() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", createVectorMetadata("vector1", 1L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", null, null, false, false, null, null);
    
    assertEquals(1, response.vectors().size());
    assertNull(response.vectors().get(0).getData());
  }

  @Test
  void listVectors_withReturnMetadataTrue_includesMetadata() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    VectorObjectMetadata vectorMetadata = createVectorMetadata("vector1", 1L);
    JsonNode metadata = new ObjectMapper().valueToTree(Map.of("key", "value"));
    vectorMetadata.setMetadata(metadata);
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", vectorMetadata);
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", null, null, false, true, null, null);
    
    assertEquals(1, response.vectors().size());
    assertEquals(metadata, response.vectors().get(0).getMetadata());
  }

  @Test
  void listVectors_withReturnMetadataFalse_excludesMetadata() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", createVectorMetadata("vector1", 1L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", null, null, false, false, null, null);
    
    assertEquals(1, response.vectors().size());
    assertNull(response.vectors().get(0).getMetadata());
  }

  @Test
  void listVectors_withValidSegmentation_returnsSegmentedResults() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    for (int i = 1; i <= 6; i++) {
      vectorObjects.put("vector" + i, createVectorMetadata("vector" + i, (long) i));
    }
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", null, null, false, false, 3, 1);
    
    List<String> expected = vectorObjects.keySet().stream().sorted()
        .filter(id -> Math.floorMod(id.hashCode(), 3) == 1).toList();
    assertFalse(expected.isEmpty());
    assertEquals(expected, keys(response));
  }

  @Test
  void listVectors_withOnlySegmentCount_throwsException() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    assertThrows(LocalS3VectorException.class,
        () -> service.listVectors("bucket", "index", null, null, false, false, 3, null));
  }

  @Test
  void listVectors_withOnlySegmentIndex_throwsException() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    assertThrows(LocalS3VectorException.class,
        () -> service.listVectors("bucket", "index", null, null, false, false, null, 1));
  }

  @Test
  void listVectors_withInvalidSegmentCount_throwsException() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    assertThrows(LocalS3VectorException.class,
        () -> service.listVectors("bucket", "index", null, null, false, false, 0, 0));
    
    assertThrows(LocalS3VectorException.class,
        () -> service.listVectors("bucket", "index", null, null, false, false, 17, 0));
  }

  @Test
  void listVectors_withInvalidSegmentIndex_throwsException() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    assertThrows(LocalS3VectorException.class,
        () -> service.listVectors("bucket", "index", null, null, false, false, 3, -1));
    
    assertThrows(LocalS3VectorException.class,
        () -> service.listVectors("bucket", "index", null, null, false, false, 3, 3));
  }

  @Test
  void listVectors_generatesCorrectNextToken() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    for (int i = 1; i <= 5; i++) {
      vectorObjects.put("vector" + i, createVectorMetadata("vector" + i, (long) i));
    }
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", 2, null, false, false, null, null);
    
    assertNotNull(response.nextToken());
    String expectedToken = Base64.getEncoder().encodeToString("v1:vector2".getBytes());
    assertEquals(expectedToken, response.nextToken());
  }

  @Test
  void listVectors_withNoMoreResults_returnsNullNextToken() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", createVectorMetadata("vector1", 1L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", 10, null, false, false, null, null);
    
    assertNull(response.nextToken());
  }

  @Test
  void listVectors_withEmptyIndex_returnsEmptyList() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    indexMetadata.setVectorObjects(new ConcurrentHashMap<>());
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", null, null, false, false, null, null);
    
    assertNotNull(response);
    assertNotNull(response.vectors());
    assertTrue(response.vectors().isEmpty());
    assertNull(response.nextToken());
  }

  @Test
  void listVectors_withStorageError_throwsInternalServerError() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", createVectorMetadata("vector1", 1L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    when(service.mockVectorStorage.getVectorData(1L)).thenThrow(new RuntimeException("Storage error"));
    
    LocalS3VectorException e = assertThrows(LocalS3VectorException.class,
        () -> service.listVectors("bucket", "index", null, null, true, false, null, null));
    assertEquals(LocalS3VectorErrorType.INTERNAL_SERVER_ERROR, e.getErrorType());
  }

  @Test
  void listVectors_sortsByVectorId() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vectorC", createVectorMetadata("vectorC", 3L));
    vectorObjects.put("vectorA", createVectorMetadata("vectorA", 1L));
    vectorObjects.put("vectorB", createVectorMetadata("vectorB", 2L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", null, null, false, false, null, null);
    
    assertEquals(3, response.vectors().size());
    assertEquals("vectorA", response.vectors().get(0).getKey());
    assertEquals("vectorB", response.vectors().get(1).getKey());
    assertEquals("vectorC", response.vectors().get(2).getKey());
  }

  @Test
  void listVectors_withNegativeMaxResults_usesDefault() {
    TestListVectorsService service = new TestListVectorsService();
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    
    Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
    vectorObjects.put("vector1", createVectorMetadata("vector1", 1L));
    indexMetadata.setVectorObjects(vectorObjects);
    
    service.bucketMap.put("bucket", bucketMetadata);
    
    ListVectorsResponse response = service.listVectors("bucket", "index", -5, null, false, false, null, null);
    
    assertEquals(1, response.vectors().size());
  }

  private VectorIndexMetadata indexWithVectors(TestListVectorsService service, String... ids) {
    VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
    bucketMetadata.setVectorBucketName("bucket");
    VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
    indexMetadata.setIndexName("index");
    bucketMetadata.putIndexMetadata("index", indexMetadata);
    for (int i = 0; i < ids.length; i++) {
      indexMetadata.getVectorObjects().put(ids[i], createVectorMetadata(ids[i], (long) i));
    }
    service.bucketMap.put("bucket", bucketMetadata);
    return indexMetadata;
  }

  private List<List<String>> listSegments(TestListVectorsService service, int segmentCount) {
    List<List<String>> segments = new ArrayList<>();
    for (int segment = 0; segment < segmentCount; segment++) {
      List<String> ids = new ArrayList<>();
      String token = null;
      do {
        ListVectorsResponse page = service.listVectors("bucket", "index", 3, token, false, false, segmentCount, segment);
        ids.addAll(keys(page));
        token = page.nextToken();
      } while (token != null);
      segments.add(ids);
    }
    return segments;
  }

  private static List<String> keys(ListVectorsResponse response) {
    return response.vectors().stream().map(ListOutputVector::getKey).toList();
  }

  private VectorObjectMetadata createVectorMetadata(String vectorId, Long storageId) {
    VectorObjectMetadata metadata = new VectorObjectMetadata();
    metadata.setVectorId(vectorId);
    metadata.setStorageId(storageId);
    return metadata;
  }

  // Test implementation class
  private static class TestListVectorsService implements ListVectorsService {

    private final com.robothy.s3.core.service.BucketGuard bucketGuard =
        com.robothy.s3.core.service.BucketGuard.inMemory();

    @Override
    public com.robothy.s3.core.service.BucketGuard bucketGuard() {
      return bucketGuard;
    }

    private final LocalS3VectorsMetadata mockMetadata = mock(LocalS3VectorsMetadata.class);
    final VectorStorage mockVectorStorage = mock(VectorStorage.class);
    final Map<String, VectorBucketMetadata> bucketMap = new HashMap<>();

    public TestListVectorsService() {
      when(mockMetadata.getVectorBucketMetadataMap()).thenReturn(bucketMap);
    }

    @Override
    public LocalS3VectorsMetadata metadata() {
      return mockMetadata;
    }

    @Override
    public VectorStorage vectorStorage() {
      return mockVectorStorage;
    }
  }
}
