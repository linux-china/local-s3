package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.robothy.s3.core.iceberg.IcebergCatalogException;
import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.iceberg.IcebergIdentifier;
import com.robothy.s3.core.iceberg.IcebergJson;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.core.service.manager.iceberg.LocalS3IcebergManager;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.apache.iceberg.MetadataUpdateParser;
import org.apache.iceberg.UpdateRequirementParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Whether the Iceberg catalog of LocalS3 knows every update and every requirement that an Iceberg client can send.
 *
 * <p>This is the drift detector that {@linkplain IcebergRestCatalogComplianceTest} isn't. The suites of Iceberg exercise
 * the catalog through its client, so they only reach what that client happens to send for the operations they perform; a
 * {@code MetadataUpdate} that a new Iceberg release adds, and that only an engine or a newer client sends, would pass
 * them unnoticed. LocalS3 refuses an update it doesn't know — a commit applied in part would hand the client metadata
 * that isn't what it asked for — so an unknown update is not a silent gap but a failed commit for whoever sends it.
 *
 * <p>The names are read out of the parsers of the Iceberg library rather than listed here, so bumping the {@code iceberg}
 * version is what runs the check: an action or a requirement that the release added and LocalS3 hasn't implemented fails
 * this test, with the name to implement.
 */
@Tag("data-tools")
class IcebergProtocolCoverageTest {

  private static final String SCHEMA = """
      {"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"}]}""";

  private static final List<String> NAMESPACE = List.of("ns");

  private static final IcebergIdentifier TABLE = IcebergIdentifier.of(NAMESPACE, "tbl");

  private static final IcebergIdentifier VIEW = IcebergIdentifier.of(NAMESPACE, "v");

  private IcebergCatalogService catalog;

  @BeforeEach
  void createCatalog() {
    LocalS3Manager s3Manager = LocalS3Manager.createInMemoryS3Manager();
    s3Manager.bucketService().createBucket("warehouse");
    catalog = LocalS3IcebergManager.createInMemory(null, s3Manager.bucketService(), s3Manager.objectService(),
        "s3://warehouse/", false).icebergCatalogService();

    ObjectNode namespace = IcebergJson.newObject();
    namespace.putArray("namespace").add(NAMESPACE.get(0));
    catalog.createNamespace(namespace);
    catalog.createTable(NAMESPACE, IcebergJson.read("""
        {"name":"tbl","schema":%s}""".formatted(SCHEMA)));
    catalog.createView(NAMESPACE, IcebergJson.read("""
        {"name":"v","schema":%s,"view-version":{"version-id":1,"schema-id":0,"timestamp-ms":1,"summary":{},
        "default-namespace":["ns"],"representations":[{"type":"sql","sql":"select 1","dialect":"spark"}]}}"""
        .formatted(SCHEMA)));
  }

  @Test
  void the_catalog_knows_every_metadata_update_of_the_iceberg_client() {
    Collection<String> actions = namesOf(MetadataUpdateParser.class, "ACTIONS");
    assertFalse(actions.isEmpty(), "The actions of MetadataUpdateParser could not be read.");
    for (String action : actions) {
      ObjectNode update = IcebergJson.newObject();
      update.put("action", action);
      // The update carries nothing but its action, so a known one fails on a missing field of its own rather than on
      // the action, and either way it is not answered "Unsupported".
      String table = failureOf(TABLE, false, "updates", update);
      String view = failureOf(VIEW, true, "updates", update.deepCopy());
      if (unsupported(table) && unsupported(view)) {
        fail("The Iceberg client can send the update '" + action + "', which the catalog of LocalS3 refuses as an"
            + " update it doesn't know: a table commit is answered \"" + table + "\" and a view commit \"" + view
            + "\". Implement it in IcebergMetadataUpdater.");
      }
    }
  }

  @Test
  void the_catalog_knows_every_commit_requirement_of_the_iceberg_client() {
    Collection<String> types = namesOf(UpdateRequirementParser.class, "TYPES");
    assertFalse(types.isEmpty(), "The requirement types of UpdateRequirementParser could not be read.");
    for (String type : types) {
      ObjectNode requirement = IcebergJson.newObject();
      requirement.put("type", type);
      String table = failureOf(TABLE, false, "requirements", requirement);
      String view = failureOf(VIEW, true, "requirements", requirement.deepCopy());
      if (unsupported(table) && unsupported(view)) {
        fail("The Iceberg client can send the requirement '" + type + "', which the catalog of LocalS3 refuses as a"
            + " requirement it doesn't know: a table commit is answered \"" + table + "\" and a view commit \""
            + view + "\". Implement it in IcebergMetadataUpdater.checkRequirements().");
      }
    }
  }

  /**
   * Commit one update, or one requirement, to a table or a view.
   *
   * @return the message the catalog refused the commit with; {@code null} if it accepted it.
   */
  private String failureOf(IcebergIdentifier identifier, boolean view, String field, ObjectNode element) {
    ObjectNode request = IcebergJson.newObject();
    request.putArray(field).add(element);
    try {
      catalog.updateTable(identifier, request, view);
      return null;
    } catch (IcebergCatalogException e) {
      return e.getMessage();
    }
  }

  /**
   * Whether a commit was refused because the catalog doesn't know what it was asked to do, rather than because of what
   * this test left out of the update.
   */
  private static boolean unsupported(String failure) {
    return failure != null && failure.startsWith("Unsupported ");
  }

  /**
   * The names of the updates, or of the requirements, that the Iceberg library can write, read out of the map its
   * parser keeps them in. Reading them rather than listing them is the point: a name that a new release adds shows up
   * here on its own.
   */
  private static Collection<String> namesOf(Class<?> parser, String mapField) {
    try {
      Field field = parser.getDeclaredField(mapField);
      field.setAccessible(true);
      return new TreeSet<>(((Map<?, ?>) field.get(null)).values().stream().map(String::valueOf).toList());
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("The names of " + parser.getSimpleName() + "." + mapField + " could not be read; the"
          + " Iceberg library changed how it keeps them, and this test has to be adjusted.", e);
    }
  }

}
