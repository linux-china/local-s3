package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.datatypes.response.S3Error;
import com.robothy.s3.rest.netty.LocalS3HttpMessageHandler;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The modules carry the GraalVM reachability metadata that a native executable embedding LocalS3 needs, which the
 * build generates from their classes, see buildSrc/src/main/groovy/local-s3.native-metadata.gradle. Without it, the
 * first request of such an executable fails, which no test on the JVM would notice.
 */
class NativeImageMetadataTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void everyModuleRegistersItsClassesForReflection() throws Exception {
    // A class that an error response serializes, one that a PERSISTENCE service reads back, and one of the server.
    assertRegisteredWithAllMembers("local-s3-datatypes", S3Error.class);
    assertRegisteredWithAllMembers("local-s3-core", VectorBucketMetadata.class);
    assertRegisteredWithAllMembers("local-s3-rest", LocalS3HttpMessageHandler.class);
  }

  @Test
  void registersTheJdkCollectionsThatJacksonInstantiates() throws Exception {
    // The type of the tags of a vector bucket, which Jackson instantiates by its constructor.
    assertTrue(types("local-s3-datatypes").contains(ConcurrentSkipListMap.class.getName()));
  }

  @Test
  void registersThePageOfTheConsole() throws Exception {
    JsonNode resources = metadata("local-s3-rest").get("resources");
    assertNotNull(resources);
    String glob = resources.get(0).get("glob").asString();
    assertNotNull(getClass().getClassLoader().getResource(glob), "The resource " + glob + " exists.");
  }

  private static void assertRegisteredWithAllMembers(String module, Class<?> type) throws Exception {
    for (JsonNode entry : metadata(module).get("reflection")) {
      if (type.getName().equals(entry.path("type").asString())) {
        assertTrue(entry.path("allDeclaredConstructors").asBoolean()
            && entry.path("allDeclaredMethods").asBoolean() && entry.path("allDeclaredFields").asBoolean(), entry.toString());
        return;
      }
    }
    throw new AssertionError(type.getName() + " isn't registered by " + module + ".");
  }

  private static Set<String> types(String module) throws Exception {
    Set<String> types = new HashSet<>();
    metadata(module).get("reflection").forEach(entry -> types.add(entry.path("type").asString()));
    return types;
  }

  private static JsonNode metadata(String module) throws Exception {
    String path = "META-INF/native-image/io.github.robothy/" + module + "/reachability-metadata.json";
    try (InputStream in = NativeImageMetadataTest.class.getClassLoader().getResourceAsStream(path)) {
      assertNotNull(in, path + " is on the class path.");
      return MAPPER.readTree(in);
    }
  }

}
