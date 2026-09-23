package com.robothy.s3.core.s3tables;

import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.iceberg.IcebergIdentifier;
import com.robothy.s3.core.iceberg.IcebergJson;
import com.robothy.s3.core.service.BucketService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations_Amazon_S3_Tables.html">Amazon S3
 * Tables</a> API of a LocalS3 service: table buckets, the namespaces and the tables in them, and the metadata
 * locations that make a commit.
 *
 * <p><b>A table bucket is a catalog.</b> Every table bucket gets an
 * {@linkplain IcebergCatalogService Iceberg catalog} of its own, and that catalog is the one truth about what the
 * table bucket holds. So the two ways into a table bucket are two views of the same thing rather than two copies of
 * it:
 *
 * <ul>
 *   <li>the control plane here, which an {@code S3TablesClient} of the AWS SDK and the {@code s3-tables-catalog}
 *       library speak — {@code CreateTable} then {@code UpdateTableMetadataLocation} for every commit; and</li>
 *   <li>the <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-integrating-open-source.html">
 *       Iceberg REST endpoint</a> of the same table bucket, which Spark, Trino, PyIceberg and DuckDB speak, reached by
 *       giving a REST catalog the ARN of the table bucket as its warehouse.</li>
 * </ul>
 *
 * <p>A table created with either is the table the other loads, and a commit made with either is the one the other then
 * reads. That is the whole point of serving this API at all: it is where the engines are going, and a local lakehouse
 * that only answered one of the two halves would send a test down a path its production code doesn't take. The
 * bookkeeping that makes it hold is in {@linkplain #tableOf} and {@linkplain #atCurrentMetadata}: a table that appeared
 * in the catalog is given the fields this API answers the first time it is asked about, and a commit made over REST
 * draws a new version token the next time this API reads the table, so the token a client is still holding is refused.
 *
 * <p><b>Where the files go.</b> A table bucket of Amazon S3 keeps its files out of reach of the S3 API. LocalS3 keeps
 * them in an ordinary bucket of the same service, named {@code <table-bucket>--table-s3}, which is what a table's
 * {@code warehouseLocation} points into. The engine that loaded a table then writes its data files there with its own
 * {@code S3FileIO}, against the same endpoint and with the same credentials, and a test can look at what was written
 * with an {@code S3Client} — which against the real service it could not.
 *
 * <p>This class is HTTP-free: it takes and answers the JSON documents of the API, not requests, so that the controller
 * stays thin and the API can be tested without a socket. The documents are trees rather than classes for the reason
 * {@linkplain IcebergJson} gives — the shapes of this API nest deeply into the Iceberg specification, and carrying a
 * field through unread is better than dropping what a newer client sent.
 */
public final class S3TablesService {

  private static final Logger log = LoggerFactory.getLogger(S3TablesService.class);

  /**
   * The suffix of the S3 bucket that holds the tables of a table bucket. Amazon names the underlying bucket of a table
   * bucket the same way, e.g. {@code amzn-s3-demo-bucket--table-s3}, so a client that parses a warehouse location sees
   * the shape it expects.
   */
  public static final String WAREHOUSE_BUCKET_SUFFIX = "--table-s3";

  /**
   * The only open table format that this API serves.
   */
  private static final String FORMAT_ICEBERG = "ICEBERG";

  /**
   * The names that a table bucket, a namespace or a table may have. Amazon allows lower-case letters, digits,
   * hyphens and underscores; a name of another shape is refused here rather than turning into a bucket name that
   * Amazon S3 wouldn't take.
   */
  private static final Pattern NAME = Pattern.compile("[0-9a-z_-]+");

  /**
   * The largest page that a list operation answers, and the page it answers when the request asks for none.
   */
  private static final int DEFAULT_PAGE_SIZE = 1000;

  /**
   * The one type of maintenance that a table bucket has.
   */
  private static final String BUCKET_MAINTENANCE_TYPE = "icebergUnreferencedFileRemoval";

  /**
   * The types of maintenance that a table has.
   */
  private static final List<String> TABLE_MAINTENANCE_TYPES = List.of("icebergCompaction", "icebergSnapshotManagement");

  private final S3TablesStore store;

  private final BucketService bucketService;

  private final S3TablesCatalogFactory catalogFactory;

  /**
   * The catalogs of the table buckets, opened once each. A catalog holds two maps of the store of the service, so
   * opening one per request would open the same maps again and again.
   */
  private final Map<String, IcebergCatalogService> catalogs = new ConcurrentHashMap<>();

  private final String region;

  private final String accountId;

  /**
   * Create the service.
   *
   * @param store the table buckets of the service.
   * @param bucketService the buckets of the service, which the bucket behind a table bucket is created and deleted
   *     through.
   * @param catalogFactory opens the Iceberg catalog of a table bucket.
   * @param region the region that the ARNs of this service name.
   * @param accountId the account that the ARNs of this service name.
   */
  public S3TablesService(S3TablesStore store, BucketService bucketService, S3TablesCatalogFactory catalogFactory,
                         String region, String accountId) {
    this.store = Objects.requireNonNull(store, "store");
    this.bucketService = Objects.requireNonNull(bucketService, "bucketService");
    this.catalogFactory = Objects.requireNonNull(catalogFactory, "catalogFactory");
    this.region = Objects.requireNonNull(region, "region");
    this.accountId = Objects.requireNonNull(accountId, "accountId");
  }

  /*
   * Table buckets.
   */

  /**
   * Create a table bucket, and the bucket of the service that its tables are written to.
   *
   * @param request a {@code CreateTableBucketRequest}: its {@code name}, and optionally its
   *     {@code encryptionConfiguration}, {@code storageClassConfiguration} and {@code tags}.
   * @return a {@code CreateTableBucketResponse}: the ARN of the table bucket.
   * @throws S3TablesException if the name is invalid, or a table bucket of that name exists.
   */
  public ObjectNode createTableBucket(ObjectNode request) {
    String name = requireName(request, "name");
    Map<String, String> configuration = new LinkedHashMap<>();
    put(configuration, TableBucketRecord.ENCRYPTION, request.get("encryptionConfiguration"));
    put(configuration, TableBucketRecord.STORAGE_CLASS, request.get("storageClassConfiguration"));

    TableBucketRecord record = TableBucketRecord.created(name, UUID.randomUUID().toString(), accountId,
        "s3://" + warehouseBucket(name), configuration);
    if (!store.putBucketIfAbsent(record)) {
      throw S3TablesException.conflict("A table bucket named '" + name + "' already exists.");
    }
    createWarehouseBucket(record);
    Map<String, String> tags = IcebergJson.toStringMap(request.get("tags"));
    if (!tags.isEmpty()) {
      store.putTags(bucketArn(name), tags);
    }

    ObjectNode response = IcebergJson.newObject();
    response.put("arn", bucketArn(name));
    return response;
  }

  /**
   * The table buckets of the service.
   *
   * @param prefix the prefix of the names to answer; {@code null} for all of them.
   * @param continuationToken the token of the previous page; {@code null} for the first page.
   * @param maxBuckets the largest page to answer; {@code null} for the default.
   * @param type {@code customer} or {@code aws}; {@code null} for both. Every table bucket of LocalS3 is a
   *     {@code customer} one.
   * @return a {@code ListTableBucketsResponse}.
   */
  public ObjectNode listTableBuckets(@Nullable String prefix, @Nullable String continuationToken,
                                     @Nullable Integer maxBuckets, @Nullable String type) {
    List<TableBucketRecord> all = new ArrayList<>(store.listBuckets());
    all.sort(Comparator.comparing(TableBucketRecord::name));
    List<TableBucketRecord> matching = new ArrayList<>();
    for (TableBucketRecord record : all) {
      if (prefix != null && !record.name().startsWith(prefix)) {
        continue;
      }
      if (type != null && !type.equals(record.type())) {
        continue;
      }
      if (continuationToken != null && record.name().compareTo(continuationToken) <= 0) {
        continue;
      }
      matching.add(record);
    }

    int limit = pageSize(maxBuckets);
    ArrayNode buckets = IcebergJson.newArray();
    for (int i = 0; i < Math.min(limit, matching.size()); i++) {
      TableBucketRecord record = matching.get(i);
      ObjectNode summary = IcebergJson.newObject();
      summary.put("arn", bucketArn(record.name()));
      summary.put("name", record.name());
      summary.put("ownerAccountId", record.ownerAccountId());
      summary.put("createdAt", timestamp(record.createdAt()));
      summary.put("tableBucketId", record.tableBucketId());
      summary.put("type", record.type());
      buckets.add(summary);
    }
    ObjectNode response = IcebergJson.newObject();
    response.set("tableBuckets", buckets);
    if (matching.size() > limit) {
      response.put("continuationToken", matching.get(limit - 1).name());
    }
    return response;
  }

  /**
   * A table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @return a {@code GetTableBucketResponse}.
   * @throws S3TablesException if it doesn't exist.
   */
  public ObjectNode getTableBucket(String tableBucketArn) {
    TableBucketRecord record = requireBucket(tableBucketArn);
    ObjectNode response = IcebergJson.newObject();
    response.put("arn", bucketArn(record.name()));
    response.put("name", record.name());
    response.put("ownerAccountId", record.ownerAccountId());
    response.put("createdAt", timestamp(record.createdAt()));
    response.put("tableBucketId", record.tableBucketId());
    response.put("type", record.type());
    return response;
  }

  /**
   * Delete a table bucket, which must hold no namespace, and the bucket of the service behind it.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @throws S3TablesException if it doesn't exist, or still holds a namespace.
   */
  public void deleteTableBucket(String tableBucketArn) {
    TableBucketRecord record = requireBucket(tableBucketArn);
    if (!namespaceNames(record).isEmpty()) {
      throw S3TablesException.conflict("The table bucket '" + record.name()
          + "' is not empty: delete its namespaces first.");
    }
    store.removeBucket(record.name());
    store.removeTags(bucketArn(record.name()));
    catalogs.remove(record.name());
    deleteWarehouseBucket(record);
  }

  /*
   * Namespaces.
   */

  /**
   * Create a namespace in a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param request a {@code CreateNamespaceRequest}: its {@code namespace}, a list of one name.
   * @return a {@code CreateNamespaceResponse}.
   * @throws S3TablesException if the table bucket doesn't exist, or the namespace does.
   */
  public ObjectNode createNamespace(String tableBucketArn, ObjectNode request) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    String namespace = requireNamespaceOf(request);
    IcebergCatalogService catalog = catalog(bucket);
    if (catalog.namespaceExists(List.of(namespace))) {
      throw S3TablesException.conflict("A namespace named '" + namespace + "' already exists in the table bucket '"
          + bucket.name() + "'.");
    }
    ObjectNode create = IcebergJson.newObject();
    create.set("namespace", IcebergJson.newArray().add(namespace));
    catalog.createNamespace(create);
    store.putNamespace(bucket.name(), namespace,
        new NamespaceAttributes(UUID.randomUUID().toString(), System.currentTimeMillis(), accountId));

    ObjectNode response = IcebergJson.newObject();
    response.put("tableBucketARN", bucketArn(bucket.name()));
    response.set("namespace", IcebergJson.newArray().add(namespace));
    return response;
  }

  /**
   * The namespaces of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param prefix the prefix of the names to answer; {@code null} for all of them.
   * @param continuationToken the token of the previous page; {@code null} for the first page.
   * @param maxNamespaces the largest page to answer; {@code null} for the default.
   * @return a {@code ListNamespacesResponse}.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public ObjectNode listNamespaces(String tableBucketArn, @Nullable String prefix,
                                   @Nullable String continuationToken, @Nullable Integer maxNamespaces) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    List<String> matching = new ArrayList<>();
    for (String name : namespaceNames(bucket)) {
      if (prefix != null && !name.startsWith(prefix)) {
        continue;
      }
      if (continuationToken != null && name.compareTo(continuationToken) <= 0) {
        continue;
      }
      matching.add(name);
    }

    int limit = pageSize(maxNamespaces);
    ArrayNode namespaces = IcebergJson.newArray();
    for (int i = 0; i < Math.min(limit, matching.size()); i++) {
      namespaces.add(namespaceSummary(bucket, matching.get(i)));
    }
    ObjectNode response = IcebergJson.newObject();
    response.set("namespaces", namespaces);
    if (matching.size() > limit) {
      response.put("continuationToken", matching.get(limit - 1));
    }
    return response;
  }

  /**
   * A namespace.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @return a {@code GetNamespaceResponse}.
   * @throws S3TablesException if the table bucket or the namespace doesn't exist.
   */
  public ObjectNode getNamespace(String tableBucketArn, String namespace) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    requireNamespace(bucket, namespace);
    return namespaceSummary(bucket, namespace);
  }

  /**
   * Delete a namespace, which must hold no table.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @throws S3TablesException if it doesn't exist, or still holds a table.
   */
  public void deleteNamespace(String tableBucketArn, String namespace) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    requireNamespace(bucket, namespace);
    IcebergCatalogService catalog = catalog(bucket);
    if (!tableNames(bucket, namespace).isEmpty()) {
      throw S3TablesException.conflict("The namespace '" + namespace + "' is not empty: delete its tables first.");
    }
    catalog.dropNamespace(List.of(namespace));
    store.removeNamespace(bucket.name(), namespace);
  }

  /*
   * Tables.
   */

  /**
   * Create a table in a namespace.
   *
   * <p>The metadata of the request is optional, and both ways through are served. A request that carries a schema
   * gets a table whose first metadata file holds it, which is what an engine that creates a table in one step sends.
   * A request that carries none gets a table with an empty schema, which is what the {@code s3-tables-catalog}
   * library sends before it writes the real metadata itself and commits it with
   * {@linkplain #updateTableMetadataLocation}.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param request a {@code CreateTableRequest}: its {@code name}, its {@code format}, and optionally its
   *     {@code metadata}, {@code encryptionConfiguration}, {@code storageClassConfiguration} and {@code tags}.
   * @return a {@code CreateTableResponse}: the ARN of the table and its first version token.
   * @throws S3TablesException if the namespace doesn't exist, the name is taken, or the format isn't
   *     {@code ICEBERG}.
   */
  public ObjectNode createTable(String tableBucketArn, String namespace, ObjectNode request) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    requireNamespace(bucket, namespace);
    String name = requireName(request, "name");
    requireIcebergFormat(request.path("format").asString(FORMAT_ICEBERG));
    if (tableOf(bucket, namespace, name) != null) {
      throw S3TablesException.conflict("A table named '" + name + "' already exists in the namespace '"
          + namespace + "'.");
    }

    ObjectNode create = icebergCreateTableRequest(name, request.path("metadata").path("iceberg"));
    ObjectNode created = catalog(bucket).createTable(List.of(namespace), create);
    String location = created.path("metadata").path("location").asString(bucket.warehouse());
    String metadataLocation = created.path("metadata-location").asString(null);

    Map<String, String> configuration = new LinkedHashMap<>();
    put(configuration, TableAttributes.ENCRYPTION, request.get("encryptionConfiguration"));
    put(configuration, TableAttributes.STORAGE_CLASS, request.get("storageClassConfiguration"));
    TableAttributes attributes = TableAttributes.created(UUID.randomUUID().toString(), namespace, name, accountId,
        location, metadataLocation, configuration);
    store.putTable(bucket.name(), attributes);
    Map<String, String> tags = IcebergJson.toStringMap(request.get("tags"));
    if (!tags.isEmpty()) {
      store.putTags(tableArn(bucket.name(), attributes.tableId()), tags);
    }

    ObjectNode response = IcebergJson.newObject();
    response.put("tableARN", tableArn(bucket.name(), attributes.tableId()));
    response.put("versionToken", attributes.versionToken());
    return response;
  }

  /**
   * The tables of a table bucket, or of one of its namespaces.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace; {@code null} for every namespace of the table bucket.
   * @param prefix the prefix of the names to answer; {@code null} for all of them.
   * @param continuationToken the token of the previous page; {@code null} for the first page.
   * @param maxTables the largest page to answer; {@code null} for the default.
   * @return a {@code ListTablesResponse}.
   * @throws S3TablesException if the table bucket, or the namespace, doesn't exist.
   */
  public ObjectNode listTables(String tableBucketArn, @Nullable String namespace, @Nullable String prefix,
                               @Nullable String continuationToken, @Nullable Integer maxTables) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    if (namespace != null) {
      requireNamespace(bucket, namespace);
    }
    List<TableAttributes> all = new ArrayList<>();
    for (String level : namespace != null ? List.of(namespace) : namespaceNames(bucket)) {
      for (String name : tableNames(bucket, level)) {
        TableAttributes attributes = tableOf(bucket, level, name);
        if (attributes != null) {
          all.add(attributes);
        }
      }
    }
    all.sort(Comparator.comparing(TableAttributes::namespace).thenComparing(TableAttributes::name));

    List<TableAttributes> matching = new ArrayList<>();
    for (TableAttributes table : all) {
      if (prefix != null && !table.name().startsWith(prefix)) {
        continue;
      }
      if (continuationToken != null && listKey(table).compareTo(continuationToken) <= 0) {
        continue;
      }
      matching.add(table);
    }

    int limit = pageSize(maxTables);
    ArrayNode tables = IcebergJson.newArray();
    for (int i = 0; i < Math.min(limit, matching.size()); i++) {
      TableAttributes table = matching.get(i);
      ObjectNode summary = IcebergJson.newObject();
      summary.set("namespace", IcebergJson.newArray().add(table.namespace()));
      summary.put("name", table.name());
      summary.put("type", table.type());
      summary.put("tableARN", tableArn(bucket.name(), table.tableId()));
      summary.put("createdAt", timestamp(table.createdAt()));
      summary.put("modifiedAt", timestamp(table.modifiedAt()));
      summary.put("namespaceId", namespaceId(bucket, table.namespace()));
      summary.put("tableBucketId", bucket.tableBucketId());
      tables.add(summary);
    }
    ObjectNode response = IcebergJson.newObject();
    response.set("tables", tables);
    if (matching.size() > limit) {
      response.put("continuationToken", listKey(matching.get(limit - 1)));
    }
    return response;
  }

  /**
   * A table, addressed either by its table bucket, namespace and name, or by its ARN.
   *
   * @param tableBucketArn the ARN of the table bucket; {@code null} if {@code tableArn} is given.
   * @param namespace the name of the namespace; {@code null} if {@code tableArn} is given.
   * @param name the name of the table; {@code null} if {@code tableArn} is given.
   * @param tableArn the ARN of the table; {@code null} if the other three are given.
   * @return a {@code GetTableResponse}.
   * @throws S3TablesException if it doesn't exist, or the request names neither form.
   */
  public ObjectNode getTable(@Nullable String tableBucketArn, @Nullable String namespace, @Nullable String name,
                             @Nullable String tableArn) {
    Located located = locate(tableBucketArn, namespace, name, tableArn);
    TableBucketRecord bucket = located.bucket();
    TableAttributes table = located.table();

    ObjectNode response = IcebergJson.newObject();
    response.put("name", table.name());
    response.put("type", table.type());
    response.put("tableARN", tableArn(bucket.name(), table.tableId()));
    response.set("namespace", IcebergJson.newArray().add(table.namespace()));
    response.put("namespaceId", namespaceId(bucket, table.namespace()));
    response.put("versionToken", table.versionToken());
    if (table.metadataLocation() != null) {
      response.put("metadataLocation", table.metadataLocation());
    }
    response.put("warehouseLocation", table.warehouseLocation());
    response.put("createdAt", timestamp(table.createdAt()));
    response.put("createdBy", table.createdBy());
    response.put("modifiedAt", timestamp(table.modifiedAt()));
    response.put("modifiedBy", table.modifiedBy());
    response.put("ownerAccountId", bucket.ownerAccountId());
    response.put("format", table.format());
    response.put("tableBucketId", bucket.tableBucketId());
    return response;
  }

  /**
   * The metadata file that a table currently is, which is how a client of this API refreshes a table: it reads the
   * location and parses the file itself.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @return a {@code GetTableMetadataLocationResponse}.
   * @throws S3TablesException if the table doesn't exist.
   */
  public ObjectNode getTableMetadataLocation(String tableBucketArn, String namespace, String name) {
    Located located = locate(tableBucketArn, namespace, name, null);
    ObjectNode response = IcebergJson.newObject();
    response.put("versionToken", located.table().versionToken());
    if (located.table().metadataLocation() != null) {
      response.put("metadataLocation", located.table().metadataLocation());
    }
    response.put("warehouseLocation", located.table().warehouseLocation());
    return response;
  }

  /**
   * Move a table to a metadata file that the client wrote, which is the commit of this API.
   *
   * <p>The {@code versionToken} of the request is the optimistic lock: it must be the one the client read the table
   * at, or the commit is refused with {@code ConflictException} and the client refreshes and retries. Both locks are
   * taken — the token here, which is claimed first, and the compare-and-set of the catalog under it — so a commit made
   * over the Iceberg REST endpoint of the same table bucket races safely against one made here.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @param request an {@code UpdateTableMetadataLocationRequest}: its {@code versionToken} and its
   *     {@code metadataLocation}.
   * @return an {@code UpdateTableMetadataLocationResponse}, at the new version token.
   * @throws S3TablesException if the table doesn't exist, or the token is stale.
   */
  public ObjectNode updateTableMetadataLocation(String tableBucketArn, String namespace, String name,
                                                ObjectNode request) {
    Located located = locate(tableBucketArn, namespace, name, null);
    TableBucketRecord bucket = located.bucket();
    TableAttributes table = located.table();
    String versionToken = requireName(request, "versionToken");
    String metadataLocation = requireName(request, "metadataLocation");
    requireCurrentVersion(table, versionToken);

    // The token is claimed first, so that a second commit that read the same token loses here rather than reaching the
    // catalog; the catalog's own compare-and-set then guards against a commit made over the REST endpoint.
    TableAttributes updated = table.committed(metadataLocation);
    if (!store.replaceTable(bucket.name(), table, updated)) {
      throw S3TablesException.conflict("The table '" + name + "' changed while this commit was being prepared."
          + " Read it again and retry.");
    }
    try {
      catalog(bucket).setMetadataLocation(IcebergIdentifier.of(List.of(namespace), name), metadataLocation);
    } catch (RuntimeException e) {
      // The commit didn't land, so the token it claimed is given back rather than left advanced.
      store.replaceTable(bucket.name(), updated, table);
      throw e;
    }

    ObjectNode response = IcebergJson.newObject();
    response.put("name", updated.name());
    response.put("tableARN", tableArn(bucket.name(), updated.tableId()));
    response.set("namespace", IcebergJson.newArray().add(updated.namespace()));
    response.put("versionToken", updated.versionToken());
    response.put("metadataLocation", metadataLocation);
    return response;
  }

  /**
   * Rename a table, or move it to another namespace.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace it is in.
   * @param name the name it has.
   * @param request a {@code RenameTableRequest}: its {@code newNamespaceName}, its {@code newName} and optionally its
   *     {@code versionToken}.
   * @throws S3TablesException if the table doesn't exist, the destination is taken, or the token is stale.
   */
  public void renameTable(String tableBucketArn, String namespace, String name, ObjectNode request) {
    Located located = locate(tableBucketArn, namespace, name, null);
    TableBucketRecord bucket = located.bucket();
    TableAttributes table = located.table();
    String newNamespace = request.path("newNamespaceName").asString(namespace);
    String newName = request.path("newName").asString(name);
    if (newNamespace.equals(namespace) && newName.equals(name)) {
      throw S3TablesException.badRequest("The rename names neither a new namespace nor a new name.");
    }
    requireCurrentVersion(table, request.path("versionToken").asString(null));
    requireNamespace(bucket, newNamespace);
    if (tableOf(bucket, newNamespace, newName) != null) {
      throw S3TablesException.conflict("A table named '" + newName + "' already exists in the namespace '"
          + newNamespace + "'.");
    }

    ObjectNode rename = IcebergJson.newObject();
    rename.set("source", identifierNode(namespace, name));
    rename.set("destination", identifierNode(newNamespace, newName));
    catalog(bucket).renameTable(rename, false);
    store.removeTable(bucket.name(), table);
    store.putTable(bucket.name(), table.renamedTo(newNamespace, newName));
  }

  /**
   * Delete a table, and the files it wrote.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @param versionToken the token the client read the table at; {@code null} to delete it whatever it is at.
   * @throws S3TablesException if it doesn't exist, or the token is stale.
   */
  public void deleteTable(String tableBucketArn, String namespace, String name, @Nullable String versionToken) {
    Located located = locate(tableBucketArn, namespace, name, null);
    TableBucketRecord bucket = located.bucket();
    TableAttributes table = located.table();
    requireCurrentVersion(table, versionToken);
    // Purged: a table bucket owns the files of its tables, so a deleted table takes its data with it, which is what
    // Amazon does and what keeps a test's storage from filling up with the tables it dropped.
    catalog(bucket).dropTable(IcebergIdentifier.of(List.of(namespace), name), false, true);
    store.removeTable(bucket.name(), table);
    store.removeTags(tableArn(bucket.name(), table.tableId()));
  }

  /*
   * Tags.
   */

  /**
   * The tags of a table bucket or a table.
   *
   * @param resourceArn the ARN of the resource.
   * @return a {@code ListTagsForResourceResponse}.
   * @throws S3TablesException if the resource doesn't exist.
   */
  public ObjectNode listTagsForResource(String resourceArn) {
    requireResource(resourceArn);
    ObjectNode response = IcebergJson.newObject();
    response.set("tags", IcebergJson.fromStringMap(store.getTags(resourceArn)));
    return response;
  }

  /**
   * Add tags to a table bucket or a table, replacing the values of the keys that are already tagged.
   *
   * @param resourceArn the ARN of the resource.
   * @param request a {@code TagResourceRequest}: its {@code tags}.
   * @throws S3TablesException if the resource doesn't exist.
   */
  public void tagResource(String resourceArn, ObjectNode request) {
    requireResource(resourceArn);
    Map<String, String> tags = new LinkedHashMap<>(store.getTags(resourceArn));
    tags.putAll(IcebergJson.toStringMap(request.get("tags")));
    store.putTags(resourceArn, tags);
  }

  /**
   * Remove tags from a table bucket or a table.
   *
   * @param resourceArn the ARN of the resource.
   * @param tagKeys the keys to remove.
   * @throws S3TablesException if the resource doesn't exist.
   */
  public void untagResource(String resourceArn, List<String> tagKeys) {
    requireResource(resourceArn);
    Map<String, String> tags = new LinkedHashMap<>(store.getTags(resourceArn));
    tagKeys.forEach(tags::remove);
    store.putTags(resourceArn, tags);
  }

  /*
   * The Iceberg REST endpoint of a table bucket.
   */

  /**
   * The Iceberg catalog of a table bucket, which its
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-integrating-open-source.html">Iceberg
   * REST endpoint</a> serves: an engine configured with the ARN of a table bucket as its warehouse reaches the tables
   * of that table bucket, the same ones this API creates.
   *
   * @param warehouse the warehouse the client named: the ARN of a table bucket, or its name.
   * @return the catalog; {@code null} if no table bucket of that name or ARN exists, which leaves the request to the
   *     catalog that the service serves by default.
   */
  @Nullable
  public IcebergCatalogService catalogOfWarehouse(@Nullable String warehouse) {
    String name = tableBucketNameOf(warehouse);
    if (name == null) {
      return null;
    }
    TableBucketRecord record = store.getBucket(name);
    return record == null ? null : catalog(record);
  }

  /**
   * The name of the table bucket that a warehouse names, whether it named it by ARN or by name.
   *
   * @param warehouse the warehouse; {@code null} for none.
   * @return the name of the table bucket; {@code null} if the warehouse names none that exists, e.g. because it is an
   *     {@code s3://} location, or the ARN of a table bucket this service doesn't have.
   */
  @Nullable
  public String tableBucketNameOf(@Nullable String warehouse) {
    if (warehouse == null || warehouse.isBlank()) {
      return null;
    }
    String name;
    if (S3TablesArn.isArn(warehouse)) {
      try {
        name = S3TablesArn.parseBucket(warehouse).bucket();
      } catch (S3TablesException e) {
        return null;
      }
    } else {
      name = warehouse;
    }
    return store.getBucket(name) != null ? name : null;
  }

  /**
   * The ARN of a table bucket of this service.
   *
   * @param name the name of the table bucket.
   * @return the ARN.
   */
  public String bucketArn(String name) {
    return S3TablesArn.ofBucket(region, accountId, name).toString();
  }

  /**
   * Forget the catalogs that were opened, which a reset of an {@code IN_MEMORY} service does: the maps they hold were
   * cleared, and a catalog opened again reads the maps as the reset left them.
   */
  public void forgetCatalogs() {
    catalogs.clear();
  }

  /*
   * Encryption, storage class, policies, maintenance, metrics, replication and record expiration.
   *
   * LocalS3 runs none of this: there is nothing to encrypt against, no tier to move to, no policy to enforce and no
   * compaction to schedule in a service that exists so that a test doesn't have to reach a real one. What matters is
   * that a client that configures these gets its configuration back rather than an error, so that the code path under
   * test — which usually sets a configuration on the way to doing something else — runs through.
   */

  /**
   * Store the encryption configuration of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param request a {@code PutTableBucketEncryptionRequest}.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public void putTableBucketEncryption(String tableBucketArn, ObjectNode request) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    store.putBucket(bucket.with(TableBucketRecord.ENCRYPTION, requireObject(request, "encryptionConfiguration")));
  }

  /**
   * The encryption configuration of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @return a {@code GetTableBucketEncryptionResponse}.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public ObjectNode getTableBucketEncryption(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    ObjectNode response = IcebergJson.newObject();
    response.set("encryptionConfiguration", document(bucket.get(TableBucketRecord.ENCRYPTION), defaultEncryption()));
    return response;
  }

  /**
   * Remove the encryption configuration of a table bucket, which leaves it at the default.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public void deleteTableBucketEncryption(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    store.putBucket(bucket.with(TableBucketRecord.ENCRYPTION, null));
  }

  /**
   * The encryption configuration of a table: its own, or the one of its table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @return a {@code GetTableEncryptionResponse}.
   * @throws S3TablesException if the table doesn't exist.
   */
  public ObjectNode getTableEncryption(String tableBucketArn, String namespace, String name) {
    Located located = locate(tableBucketArn, namespace, name, null);
    String configuration = located.table().get(TableAttributes.ENCRYPTION) != null
        ? located.table().get(TableAttributes.ENCRYPTION) : located.bucket().get(TableBucketRecord.ENCRYPTION);
    ObjectNode response = IcebergJson.newObject();
    response.set("encryptionConfiguration", document(configuration, defaultEncryption()));
    return response;
  }

  /**
   * Store the storage class configuration of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param request a {@code PutTableBucketStorageClassRequest}.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public void putTableBucketStorageClass(String tableBucketArn, ObjectNode request) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    store.putBucket(bucket.with(TableBucketRecord.STORAGE_CLASS,
        requireObject(request, "storageClassConfiguration")));
  }

  /**
   * The storage class configuration of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @return a {@code GetTableBucketStorageClassResponse}.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public ObjectNode getTableBucketStorageClass(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    ObjectNode response = IcebergJson.newObject();
    response.set("storageClassConfiguration",
        document(bucket.get(TableBucketRecord.STORAGE_CLASS), defaultStorageClass()));
    return response;
  }

  /**
   * The storage class configuration of a table: its own, or the one of its table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @return a {@code GetTableStorageClassResponse}.
   * @throws S3TablesException if the table doesn't exist.
   */
  public ObjectNode getTableStorageClass(String tableBucketArn, String namespace, String name) {
    Located located = locate(tableBucketArn, namespace, name, null);
    String configuration = located.table().get(TableAttributes.STORAGE_CLASS) != null
        ? located.table().get(TableAttributes.STORAGE_CLASS) : located.bucket().get(TableBucketRecord.STORAGE_CLASS);
    ObjectNode response = IcebergJson.newObject();
    response.set("storageClassConfiguration", document(configuration, defaultStorageClass()));
    return response;
  }

  /**
   * Store the resource policy of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param request a {@code PutTableBucketPolicyRequest}: its {@code resourcePolicy}.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public void putTableBucketPolicy(String tableBucketArn, ObjectNode request) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    store.putBucket(bucket.with(TableBucketRecord.POLICY, requireName(request, "resourcePolicy")));
  }

  /**
   * The resource policy of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @return a {@code GetTableBucketPolicyResponse}.
   * @throws S3TablesException if the table bucket doesn't exist, or holds no policy.
   */
  public ObjectNode getTableBucketPolicy(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    String policy = bucket.get(TableBucketRecord.POLICY);
    if (policy == null) {
      throw S3TablesException.notFound("The table bucket '" + bucket.name() + "' has no resource policy.");
    }
    ObjectNode response = IcebergJson.newObject();
    response.put("resourcePolicy", policy);
    return response;
  }

  /**
   * Remove the resource policy of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public void deleteTableBucketPolicy(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    store.putBucket(bucket.with(TableBucketRecord.POLICY, null));
  }

  /**
   * Store the resource policy of a table.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @param request a {@code PutTablePolicyRequest}: its {@code resourcePolicy}.
   * @throws S3TablesException if the table doesn't exist.
   */
  public void putTablePolicy(String tableBucketArn, String namespace, String name, ObjectNode request) {
    Located located = locate(tableBucketArn, namespace, name, null);
    store.putTable(located.bucket().name(),
        located.table().with(TableAttributes.POLICY, requireName(request, "resourcePolicy")));
  }

  /**
   * The resource policy of a table.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @return a {@code GetTablePolicyResponse}.
   * @throws S3TablesException if the table doesn't exist, or holds no policy.
   */
  public ObjectNode getTablePolicy(String tableBucketArn, String namespace, String name) {
    Located located = locate(tableBucketArn, namespace, name, null);
    String policy = located.table().get(TableAttributes.POLICY);
    if (policy == null) {
      throw S3TablesException.notFound("The table '" + name + "' has no resource policy.");
    }
    ObjectNode response = IcebergJson.newObject();
    response.put("resourcePolicy", policy);
    return response;
  }

  /**
   * Remove the resource policy of a table.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @throws S3TablesException if the table doesn't exist.
   */
  public void deleteTablePolicy(String tableBucketArn, String namespace, String name) {
    Located located = locate(tableBucketArn, namespace, name, null);
    store.putTable(located.bucket().name(), located.table().with(TableAttributes.POLICY, null));
  }

  /**
   * Store one maintenance configuration of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param type the type of maintenance, which is {@code icebergUnreferencedFileRemoval}.
   * @param request a {@code PutTableBucketMaintenanceConfigurationRequest}: its {@code value}.
   * @throws S3TablesException if the table bucket doesn't exist, or the type isn't one of the API.
   */
  public void putTableBucketMaintenanceConfiguration(String tableBucketArn, String type, ObjectNode request) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    if (!BUCKET_MAINTENANCE_TYPE.equals(type)) {
      throw S3TablesException.invalidValue("type", type, List.of(BUCKET_MAINTENANCE_TYPE));
    }
    store.putBucket(bucket.with(TableBucketRecord.maintenance(type), requireObject(request, "value")));
  }

  /**
   * The maintenance configuration of a table bucket.
   *
   * <p>LocalS3 removes no files: what is answered is what was stored, or the defaults of a table bucket that never had
   * a configuration stored on it.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @return a {@code GetTableBucketMaintenanceConfigurationResponse}.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public ObjectNode getTableBucketMaintenanceConfiguration(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    ObjectNode configuration = IcebergJson.newObject();
    configuration.set(BUCKET_MAINTENANCE_TYPE,
        document(bucket.get(TableBucketRecord.maintenance(BUCKET_MAINTENANCE_TYPE)),
            maintenanceValue(BUCKET_MAINTENANCE_TYPE, "unreferencedDays", 3, "nonCurrentDays", 10)));
    ObjectNode response = IcebergJson.newObject();
    response.put("tableBucketARN", bucketArn(bucket.name()));
    response.set("configuration", configuration);
    return response;
  }

  /**
   * Store one maintenance configuration of a table.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @param type the type of maintenance: {@code icebergCompaction} or {@code icebergSnapshotManagement}.
   * @param request a {@code PutTableMaintenanceConfigurationRequest}: its {@code value}.
   * @throws S3TablesException if the table doesn't exist, or the type isn't one of the API.
   */
  public void putTableMaintenanceConfiguration(String tableBucketArn, String namespace, String name, String type,
                                               ObjectNode request) {
    Located located = locate(tableBucketArn, namespace, name, null);
    if (!TABLE_MAINTENANCE_TYPES.contains(type)) {
      throw S3TablesException.invalidValue("type", type, TABLE_MAINTENANCE_TYPES);
    }
    store.putTable(located.bucket().name(),
        located.table().with(TableAttributes.maintenance(type), requireObject(request, "value")));
  }

  /**
   * The maintenance configuration of a table.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @return a {@code GetTableMaintenanceConfigurationResponse}.
   * @throws S3TablesException if the table doesn't exist.
   */
  public ObjectNode getTableMaintenanceConfiguration(String tableBucketArn, String namespace, String name) {
    Located located = locate(tableBucketArn, namespace, name, null);
    TableAttributes table = located.table();
    ObjectNode configuration = IcebergJson.newObject();
    configuration.set("icebergCompaction",
        document(table.get(TableAttributes.maintenance("icebergCompaction")),
            maintenanceValue("icebergCompaction", "targetFileSizeMB", 512, null, 0)));
    configuration.set("icebergSnapshotManagement",
        document(table.get(TableAttributes.maintenance("icebergSnapshotManagement")),
            maintenanceValue("icebergSnapshotManagement", "minSnapshotsToKeep", 1, "maxSnapshotAgeHours", 120)));
    ObjectNode response = IcebergJson.newObject();
    response.put("tableARN", tableArn(located.bucket().name(), table.tableId()));
    response.set("configuration", configuration);
    return response;
  }

  /**
   * The status of the maintenance jobs of a table, which LocalS3 never runs: every job is answered as not yet run, so
   * that a client that polls for one gets a well-formed answer rather than an error.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @return a {@code GetTableMaintenanceJobStatusResponse}.
   * @throws S3TablesException if the table doesn't exist.
   */
  public ObjectNode getTableMaintenanceJobStatus(String tableBucketArn, String namespace, String name) {
    Located located = locate(tableBucketArn, namespace, name, null);
    ObjectNode status = IcebergJson.newObject();
    for (String job : List.of("icebergCompaction", "icebergSnapshotManagement", BUCKET_MAINTENANCE_TYPE)) {
      ObjectNode value = IcebergJson.newObject();
      value.put("status", "Not_Yet_Run");
      status.set(job, value);
    }
    ObjectNode response = IcebergJson.newObject();
    response.put("tableARN", tableArn(located.bucket().name(), located.table().tableId()));
    response.set("status", status);
    return response;
  }

  /**
   * Store the metrics configuration of a table bucket, which is an ID and nothing else.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public void putTableBucketMetricsConfiguration(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    store.putBucket(bucket.with(TableBucketRecord.METRICS_ID, UUID.randomUUID().toString()));
  }

  /**
   * The metrics configuration of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @return a {@code GetTableBucketMetricsConfigurationResponse}.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public ObjectNode getTableBucketMetricsConfiguration(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    ObjectNode response = IcebergJson.newObject();
    response.put("tableBucketARN", bucketArn(bucket.name()));
    if (bucket.get(TableBucketRecord.METRICS_ID) != null) {
      response.put("id", bucket.get(TableBucketRecord.METRICS_ID));
    }
    return response;
  }

  /**
   * Remove the metrics configuration of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public void deleteTableBucketMetricsConfiguration(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    store.putBucket(bucket.with(TableBucketRecord.METRICS_ID, null));
  }

  /**
   * Store the record expiration configuration of a table.
   *
   * @param tableArn the ARN of the table.
   * @param request a {@code PutTableRecordExpirationConfigurationRequest}: its {@code value}.
   * @throws S3TablesException if the table doesn't exist.
   */
  public void putTableRecordExpirationConfiguration(String tableArn, ObjectNode request) {
    Located located = locate(null, null, null, tableArn);
    store.putTable(located.bucket().name(),
        located.table().with(TableAttributes.RECORD_EXPIRATION, requireObject(request, "value")));
  }

  /**
   * The record expiration configuration of a table.
   *
   * @param tableArn the ARN of the table.
   * @return a {@code GetTableRecordExpirationConfigurationResponse}.
   * @throws S3TablesException if the table doesn't exist.
   */
  public ObjectNode getTableRecordExpirationConfiguration(String tableArn) {
    Located located = locate(null, null, null, tableArn);
    ObjectNode disabled = IcebergJson.newObject();
    disabled.put("status", "disabled");
    ObjectNode response = IcebergJson.newObject();
    response.set("configuration", document(located.table().get(TableAttributes.RECORD_EXPIRATION), disabled));
    return response;
  }

  /**
   * The status of the record expiration job of a table, which LocalS3 never runs.
   *
   * @param tableArn the ARN of the table.
   * @return a {@code GetTableRecordExpirationJobStatusResponse}.
   * @throws S3TablesException if the table doesn't exist.
   */
  public ObjectNode getTableRecordExpirationJobStatus(String tableArn) {
    locate(null, null, null, tableArn);
    ObjectNode response = IcebergJson.newObject();
    response.put("status", "NotYetRun");
    return response;
  }

  /**
   * Store the replication configuration of a table bucket, which LocalS3 replicates nothing by.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @param versionToken the token the client read the configuration at; {@code null} for the first one.
   * @param request a {@code PutTableBucketReplicationRequest}: its {@code configuration}.
   * @return a {@code PutTableBucketReplicationResponse}.
   * @throws S3TablesException if the table bucket doesn't exist, or the token is stale.
   */
  public ObjectNode putTableBucketReplication(String tableBucketArn, @Nullable String versionToken,
                                              ObjectNode request) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    String current = bucket.get(TableBucketRecord.REPLICATION_VERSION);
    if (versionToken != null && !versionToken.isBlank() && !versionToken.equals(current)) {
      throw S3TablesException.conflict("The version token of the request is not the current one of the replication"
          + " configuration of the table bucket '" + bucket.name() + "'.");
    }
    String token = S3TablesVersionTokens.next();
    store.putBucket(bucket.with(TableBucketRecord.REPLICATION, requireObject(request, "configuration"))
        .with(TableBucketRecord.REPLICATION_VERSION, token));
    ObjectNode response = IcebergJson.newObject();
    response.put("versionToken", token);
    response.put("status", "enabled");
    return response;
  }

  /**
   * The replication configuration of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @return a {@code GetTableBucketReplicationResponse}.
   * @throws S3TablesException if the table bucket doesn't exist, or holds no configuration.
   */
  public ObjectNode getTableBucketReplication(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    String configuration = bucket.get(TableBucketRecord.REPLICATION);
    if (configuration == null) {
      throw S3TablesException.notFound("The table bucket '" + bucket.name() + "' has no replication configuration.");
    }
    ObjectNode response = IcebergJson.newObject();
    response.put("versionToken", bucket.get(TableBucketRecord.REPLICATION_VERSION));
    response.set("configuration", IcebergJson.read(configuration));
    return response;
  }

  /**
   * Remove the replication configuration of a table bucket.
   *
   * @param tableBucketArn the ARN of the table bucket.
   * @throws S3TablesException if the table bucket doesn't exist.
   */
  public void deleteTableBucketReplication(String tableBucketArn) {
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    store.putBucket(bucket.with(TableBucketRecord.REPLICATION, null)
        .with(TableBucketRecord.REPLICATION_VERSION, null));
  }

  /**
   * Store the replication configuration of a table, which LocalS3 replicates nothing by.
   *
   * @param tableArn the ARN of the table.
   * @param versionToken the token the client read the table at; {@code null} to store it whatever it is at.
   * @param request a {@code PutTableReplicationRequest}: its {@code configuration}.
   * @return a {@code PutTableReplicationResponse}.
   * @throws S3TablesException if the table doesn't exist, or the token is stale.
   */
  public ObjectNode putTableReplication(String tableArn, @Nullable String versionToken, ObjectNode request) {
    Located located = locate(null, null, null, tableArn);
    requireCurrentVersion(located.table(), versionToken);
    TableAttributes updated = located.table().withReplication(requireObject(request, "configuration"));
    store.putTable(located.bucket().name(), updated);
    ObjectNode response = IcebergJson.newObject();
    response.put("versionToken", updated.versionToken());
    response.put("status", "enabled");
    return response;
  }

  /**
   * The replication configuration of a table.
   *
   * @param tableArn the ARN of the table.
   * @return a {@code GetTableReplicationResponse}.
   * @throws S3TablesException if the table doesn't exist, or holds no configuration.
   */
  public ObjectNode getTableReplication(String tableArn) {
    Located located = locate(null, null, null, tableArn);
    String configuration = located.table().get(TableAttributes.REPLICATION);
    if (configuration == null) {
      throw S3TablesException.notFound("The table '" + located.table().name()
          + "' has no replication configuration.");
    }
    ObjectNode response = IcebergJson.newObject();
    response.put("versionToken", located.table().versionToken());
    response.set("configuration", IcebergJson.read(configuration));
    return response;
  }

  /**
   * Remove the replication configuration of a table.
   *
   * @param tableArn the ARN of the table.
   * @param versionToken the token the client read the table at; {@code null} to remove it whatever it is at.
   * @throws S3TablesException if the table doesn't exist, or the token is stale.
   */
  public void deleteTableReplication(String tableArn, @Nullable String versionToken) {
    Located located = locate(null, null, null, tableArn);
    requireCurrentVersion(located.table(), versionToken);
    store.putTable(located.bucket().name(), located.table().withReplication(null));
  }

  /**
   * The replication status of a table, which is empty: LocalS3 replicates nothing, so no destination has a status.
   *
   * @param tableArn the ARN of the table.
   * @return a {@code GetTableReplicationStatusResponse}.
   * @throws S3TablesException if the table doesn't exist.
   */
  public ObjectNode getTableReplicationStatus(String tableArn) {
    Located located = locate(null, null, null, tableArn);
    ObjectNode response = IcebergJson.newObject();
    response.put("sourceTableArn", tableArn(located.bucket().name(), located.table().tableId()));
    response.set("destinations", IcebergJson.newArray());
    return response;
  }

  /*
   * The pieces the operations are built from.
   */

  /**
   * Build the {@code CreateTableRequest} of the Iceberg REST protocol from the {@code IcebergMetadata} of an S3 Tables
   * {@code CreateTable}, which is the same table described in another shape: {@code sourceId} for {@code source-id},
   * {@code writeOrder} for {@code write-order}, and so on.
   *
   * <p>A request that carries no metadata gets an empty schema. That is not a degenerate table: it is exactly what the
   * {@code s3-tables-catalog} library creates before it writes the real metadata itself and commits it with
   * {@code UpdateTableMetadataLocation}, and a table that always has a metadata file is a table the Iceberg REST
   * endpoint of the same table bucket can load at any point.
   */
  private static ObjectNode icebergCreateTableRequest(String name, JsonNode metadata) {
    ObjectNode request = IcebergJson.newObject();
    request.put("name", name);
    request.set("schema", icebergSchema(metadata));
    JsonNode partitionSpec = metadata.get("partitionSpec");
    if (partitionSpec != null && partitionSpec.isObject()) {
      request.set("partition-spec", icebergPartitionSpec(partitionSpec));
    }
    JsonNode writeOrder = metadata.get("writeOrder");
    if (writeOrder != null && writeOrder.isObject()) {
      request.set("write-order", icebergSortOrder(writeOrder));
    }
    JsonNode properties = metadata.get("properties");
    if (properties != null && properties.isObject()) {
      request.set("properties", properties.deepCopy());
    }
    return request;
  }

  /**
   * The Iceberg schema of the {@code schema} or the {@code schemaV2} of an {@code IcebergMetadata}. The fields of a
   * {@code schema} carry their type as a string and may leave their ID out, which is then their position; the fields
   * of a {@code schemaV2} carry a type that may itself be a struct, a list or a map, and are passed through as they
   * came. The IDs are re-assigned by the catalog either way, see
   * {@code com.robothy.s3.core.iceberg.FreshSchemaIds}.
   */
  private static ObjectNode icebergSchema(JsonNode metadata) {
    ObjectNode schema = IcebergJson.newObject();
    schema.put("type", "struct");
    ArrayNode fields = IcebergJson.newArray();
    JsonNode schemaV2 = metadata.get("schemaV2");
    JsonNode source = schemaV2 != null && schemaV2.isObject() ? schemaV2 : metadata.get("schema");
    int position = 1;
    if (source != null && source.isObject()) {
      for (JsonNode field : source.path("fields")) {
        if (!field.isObject()) {
          continue;
        }
        ObjectNode converted = IcebergJson.newObject();
        converted.put("id", field.path("id").asInt(position));
        converted.put("name", requireFieldName(field));
        converted.set("type", requireFieldType(field));
        converted.put("required", field.path("required").asBoolean(false));
        if (field.hasNonNull("doc")) {
          converted.put("doc", field.path("doc").asString());
        }
        fields.add(converted);
        position++;
      }
      JsonNode identifierFieldIds = source.get("identifierFieldIds");
      if (identifierFieldIds != null && identifierFieldIds.isArray()) {
        schema.set("identifier-field-ids", identifierFieldIds.deepCopy());
      }
    }
    schema.set("fields", fields);
    return schema;
  }

  private static String requireFieldName(JsonNode field) {
    String name = field.path("name").asString(null);
    if (name == null || name.isBlank()) {
      throw S3TablesException.badRequest("Every field of the schema must have a name.");
    }
    return name;
  }

  private static JsonNode requireFieldType(JsonNode field) {
    JsonNode type = field.get("type");
    if (type == null || type.isNull()) {
      throw S3TablesException.badRequest("Every field of the schema must have a type.");
    }
    return type.deepCopy();
  }

  private static ObjectNode icebergPartitionSpec(JsonNode spec) {
    ObjectNode converted = IcebergJson.newObject();
    converted.put("spec-id", spec.path("specId").asInt(0));
    ArrayNode fields = IcebergJson.newArray();
    int fieldId = 1000;
    for (JsonNode field : spec.path("fields")) {
      ObjectNode partitionField = IcebergJson.newObject();
      partitionField.put("source-id", field.path("sourceId").asInt(0));
      partitionField.put("field-id", field.path("fieldId").asInt(fieldId));
      partitionField.put("name", field.path("name").asString(""));
      partitionField.put("transform", field.path("transform").asString("identity"));
      fields.add(partitionField);
      fieldId++;
    }
    converted.set("fields", fields);
    return converted;
  }

  private static ObjectNode icebergSortOrder(JsonNode order) {
    ObjectNode converted = IcebergJson.newObject();
    converted.put("order-id", order.path("orderId").asInt(1));
    ArrayNode fields = IcebergJson.newArray();
    for (JsonNode field : order.path("fields")) {
      ObjectNode sortField = IcebergJson.newObject();
      sortField.put("source-id", field.path("sourceId").asInt(0));
      sortField.put("transform", field.path("transform").asString("identity"));
      sortField.put("direction", field.path("direction").asString("asc"));
      sortField.put("null-order", field.path("nullOrder").asString("nulls-first"));
      fields.add(sortField);
    }
    converted.set("fields", fields);
    return converted;
  }

  /**
   * The table bucket of an ARN.
   *
   * @throws S3TablesException if the ARN names no table bucket of this service.
   */
  private TableBucketRecord requireBucket(String tableBucketArn) {
    S3TablesArn arn = S3TablesArn.parseBucket(tableBucketArn);
    TableBucketRecord record = store.getBucket(arn.bucket());
    if (record == null) {
      throw S3TablesException.notFound("No table bucket named '" + arn.bucket() + "'.");
    }
    return record;
  }

  /**
   * Check that a namespace exists, which the Iceberg catalog of the table bucket is the truth about.
   *
   * @throws S3TablesException if it doesn't.
   */
  private void requireNamespace(TableBucketRecord bucket, String namespace) {
    if (!catalog(bucket).namespaceExists(List.of(namespace))) {
      throw S3TablesException.notFound("No namespace named '" + namespace + "' in the table bucket '"
          + bucket.name() + "'.");
    }
  }

  /**
   * The namespaces of a table bucket, in order, read from its catalog: whichever of the two protocols created one, it
   * is there.
   */
  private List<String> namespaceNames(TableBucketRecord bucket) {
    List<String> names = new ArrayList<>();
    for (JsonNode levels : catalog(bucket).listNamespaces(List.of()).path("namespaces")) {
      if (levels.isArray() && !levels.isEmpty()) {
        names.add(levels.get(0).asString());
      }
    }
    names.sort(Comparator.naturalOrder());
    return names;
  }

  /**
   * The tables of a namespace of a table bucket, read from its catalog. Views are not among them: the S3 Tables API has
   * no views, and one created over the Iceberg REST endpoint is left to that endpoint.
   */
  private List<String> tableNames(TableBucketRecord bucket, String namespace) {
    List<String> names = new ArrayList<>();
    for (JsonNode identifier : catalog(bucket).listTables(List.of(namespace), false).path("identifiers")) {
      String name = identifier.path("name").asString(null);
      if (name != null) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * The attributes of a table of a table bucket, given to it if it has none.
   *
   * <p>A table that was created over the Iceberg REST endpoint of the table bucket is in the catalog but has no ID, no
   * version token and no timestamps, because nothing of this API created it. It is given them the first time this API
   * is asked about it, and keeps them: so the table an engine created is a table this API can then address by ARN,
   * commit to and delete, rather than one it claims never existed.
   *
   * @return the attributes; {@code null} if the catalog holds no such table.
   */
  @Nullable
  private TableAttributes tableOf(TableBucketRecord bucket, String namespace, String name) {
    TableAttributes stored = store.getTable(bucket.name(), namespace, name);
    if (stored != null) {
      return stored;
    }
    IcebergIdentifier identifier = IcebergIdentifier.of(List.of(namespace), name);
    IcebergCatalogService catalog = catalog(bucket);
    if (!catalog.tableExists(identifier, false)) {
      return null;
    }
    String location = catalog.locationOf(identifier);
    TableAttributes created = TableAttributes.created(UUID.randomUUID().toString(), namespace, name, accountId,
        location != null ? location : bucket.warehouse(), catalog.metadataLocationOf(identifier), Map.of());
    if (store.putTableIfAbsent(bucket.name(), created)) {
      return created;
    }
    // Another request gave it its attributes first; those are the ones that count, so that its ARN is stable.
    TableAttributes raced = store.getTable(bucket.name(), namespace, name);
    return raced != null ? raced : created;
  }

  /**
   * The attributes of a table at the metadata file its catalog now points at.
   *
   * <p>A commit made over the Iceberg REST endpoint moves that pointer without going through this API, so the version
   * token this API vends has to follow it: the token is drawn again here, which is what makes a client that is still
   * holding the token from before that commit lose its next {@code UpdateTableMetadataLocation} instead of silently
   * overwriting the commit it never saw.
   */
  private TableAttributes atCurrentMetadata(TableBucketRecord bucket, TableAttributes table) {
    String current = metadataLocation(bucket, table);
    if (current == null || current.equals(table.metadataLocation())) {
      return table;
    }
    TableAttributes committed = table.committed(current);
    if (store.replaceTable(bucket.name(), table, committed)) {
      return committed;
    }
    TableAttributes raced = store.getTable(bucket.name(), table.namespace(), table.name());
    return raced != null ? raced : committed;
  }

  /**
   * The table that a request addresses, either by table bucket, namespace and name, or by ARN, with the table bucket
   * it is in, and at the metadata file its catalog currently points at.
   *
   * @throws S3TablesException if the request names neither form, or names nothing that exists.
   */
  private Located locate(@Nullable String tableBucketArn, @Nullable String namespace, @Nullable String name,
                         @Nullable String tableArn) {
    if (tableArn != null && !tableArn.isBlank()) {
      S3TablesArn arn = S3TablesArn.parseTable(tableArn);
      TableBucketRecord bucket = store.getBucket(arn.bucket());
      if (bucket == null) {
        throw S3TablesException.notFound("No table bucket named '" + arn.bucket() + "'.");
      }
      TableAttributes table = store.getTableById(bucket.name(), arn.tableId());
      if (table == null) {
        throw S3TablesException.notFound("No table of the ARN " + tableArn + ".");
      }
      return new Located(bucket, atCurrentMetadata(bucket, table));
    }
    if (tableBucketArn == null || namespace == null || name == null) {
      throw S3TablesException.badRequest("A table is addressed either by tableBucketARN, namespace and name, or by"
          + " tableArn.");
    }
    TableBucketRecord bucket = requireBucket(tableBucketArn);
    TableAttributes table = tableOf(bucket, namespace, name);
    if (table == null) {
      throw S3TablesException.notFound("No table named '" + name + "' in the namespace '" + namespace
          + "' of the table bucket '" + bucket.name() + "'.");
    }
    return new Located(bucket, atCurrentMetadata(bucket, table));
  }

  /**
   * Check that a table bucket or a table of an ARN exists, which the tagging operations do before they touch the tags
   * of a resource that isn't there.
   *
   * @throws S3TablesException if it doesn't.
   */
  private void requireResource(String resourceArn) {
    S3TablesArn arn = S3TablesArn.parse(resourceArn);
    if (arn.tableId() == null) {
      requireBucket(resourceArn);
    } else {
      locate(null, null, null, resourceArn);
    }
  }

  /**
   * Check that the version token a client sent is the one the table is at.
   *
   * @param table the table.
   * @param versionToken the token the client sent; {@code null} for a request that carries none, which is allowed
   *     where the API makes the token optional.
   * @throws S3TablesException if it is stale.
   */
  private static void requireCurrentVersion(TableAttributes table, @Nullable String versionToken) {
    if (versionToken != null && !versionToken.isBlank() && !versionToken.equals(table.versionToken())) {
      throw S3TablesException.conflict("The version token of the request is not the current one of the table '"
          + table.name() + "'. Read the table again and retry.");
    }
  }

  /**
   * The metadata file that the catalog of a table bucket points a table at.
   *
   * @return the location; {@code null} if the catalog no longer holds the table, which a client reads as a table that
   *     has yet to be committed to rather than as a failure.
   */
  @Nullable
  private String metadataLocation(TableBucketRecord bucket, TableAttributes table) {
    try {
      return catalog(bucket).metadataLocationOf(IcebergIdentifier.of(List.of(table.namespace()), table.name()));
    } catch (RuntimeException e) {
      log.debug("The catalog of the table bucket '{}' holds no pointer for the table '{}'.", bucket.name(),
          table.name(), e);
      return null;
    }
  }

  private ObjectNode namespaceSummary(TableBucketRecord bucket, String namespace) {
    NamespaceAttributes attributes = store.getNamespace(bucket.name(), namespace);
    ObjectNode summary = IcebergJson.newObject();
    summary.set("namespace", IcebergJson.newArray().add(namespace));
    summary.put("createdAt", timestamp(attributes == null ? bucket.createdAt() : attributes.createdAt()));
    summary.put("createdBy", attributes == null ? bucket.ownerAccountId() : attributes.createdBy());
    summary.put("ownerAccountId", bucket.ownerAccountId());
    summary.put("namespaceId", namespaceId(bucket, namespace));
    summary.put("tableBucketId", bucket.tableBucketId());
    return summary;
  }

  /**
   * The ID of a namespace; its name for a namespace created over the Iceberg REST endpoint, which assigned none.
   */
  private String namespaceId(TableBucketRecord bucket, String namespace) {
    NamespaceAttributes attributes = store.getNamespace(bucket.name(), namespace);
    return attributes == null ? namespace : attributes.namespaceId();
  }

  /**
   * The catalog of a table bucket, opened once per table bucket.
   */
  private IcebergCatalogService catalog(TableBucketRecord bucket) {
    return catalogs.computeIfAbsent(bucket.name(), name -> catalogFactory.create(name, bucket.warehouse()));
  }

  /**
   * The name of the bucket of the service that the tables of a table bucket are written to.
   *
   * @param tableBucket the name of the table bucket.
   * @return the name of the bucket, e.g. {@code sales--table-s3}.
   */
  public static String warehouseBucket(String tableBucket) {
    // A table bucket may hold '_', which an Amazon S3 bucket name may not, so it becomes '-'. Two table bucket names
    // that differ only in that would want the same bucket; the second one gets a suffix, see createWarehouseBucket.
    return tableBucket.replace('_', '-') + WAREHOUSE_BUCKET_SUFFIX;
  }

  /**
   * Create the bucket that the tables of a table bucket are written to. A name that another table bucket already took
   * is made unique rather than shared, so that deleting one table bucket can't take the files of another with it.
   */
  private void createWarehouseBucket(TableBucketRecord record) {
    String bucket = warehouseBucket(record.name());
    try {
      bucketService.getBucket(bucket);
    } catch (BucketNotExistException e) {
      bucketService.createBucket(bucket);
      log.info("Created the bucket '{}' of the table bucket '{}'.", bucket, record.name());
      return;
    }
    // Taken by another table bucket, or by a bucket of the service: this table bucket gets one of its own.
    String unique = bucket + "-" + UUID.randomUUID().toString().substring(0, 8);
    bucketService.createBucket(unique);
    store.putBucket(record.withWarehouse("s3://" + unique));
    log.info("Created the bucket '{}' of the table bucket '{}'; '{}' was taken.", unique, record.name(), bucket);
  }

  /**
   * Delete the bucket that the tables of a deleted table bucket were written to. A failure is logged rather than
   * raised: the table bucket is already gone, and a client that was told the delete failed would have nothing to do
   * about it.
   */
  private void deleteWarehouseBucket(TableBucketRecord record) {
    String bucket = record.warehouse().substring("s3://".length());
    try {
      bucketService.deleteBucket(bucket);
    } catch (LocalS3Exception e) {
      log.warn("Deleted the table bucket '{}' but not its bucket '{}': {}", record.name(), bucket, e.getMessage());
    }
  }

  private static ObjectNode identifierNode(String namespace, String name) {
    ObjectNode identifier = IcebergJson.newObject();
    identifier.set("namespace", IcebergJson.newArray().add(namespace));
    identifier.put("name", name);
    return identifier;
  }

  /**
   * A stored configuration document, or the default that a resource which never had one answers.
   */
  private static JsonNode document(@Nullable String stored, ObjectNode fallback) {
    return stored == null ? fallback : IcebergJson.read(stored);
  }

  private static ObjectNode defaultEncryption() {
    ObjectNode configuration = IcebergJson.newObject();
    configuration.put("sseAlgorithm", "AES256");
    return configuration;
  }

  private static ObjectNode defaultStorageClass() {
    ObjectNode configuration = IcebergJson.newObject();
    configuration.put("storageClass", "STANDARD");
    return configuration;
  }

  /**
   * A {@code ...MaintenanceConfigurationValue} with the settings of one maintenance type.
   */
  private static ObjectNode maintenanceValue(String type, String firstSetting, int firstValue,
                                             @Nullable String secondSetting, int secondValue) {
    ObjectNode values = IcebergJson.newObject();
    values.put(firstSetting, firstValue);
    if (secondSetting != null) {
      values.put(secondSetting, secondValue);
    }
    ObjectNode settings = IcebergJson.newObject();
    settings.set(type, values);
    ObjectNode value = IcebergJson.newObject();
    value.put("status", "enabled");
    value.set("settings", settings);
    return value;
  }

  private String tableArn(String tableBucket, String tableId) {
    return S3TablesArn.ofTable(region, accountId, tableBucket, tableId).toString();
  }

  private static String listKey(TableAttributes table) {
    return table.namespace() + '\u001F' + table.name();
  }

  private static int pageSize(@Nullable Integer requested) {
    if (requested == null || requested <= 0) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(requested, DEFAULT_PAGE_SIZE);
  }

  private static String timestamp(long epochMillis) {
    return Instant.ofEpochMilli(epochMillis).toString();
  }

  /**
   * Put a configuration document of a request into the configuration of the resource being created, if the request
   * carried one.
   */
  private static void put(Map<String, String> configuration, String key, @Nullable JsonNode value) {
    if (value != null && value.isObject()) {
      configuration.put(key, IcebergJson.write(value));
    }
  }

  /**
   * A required string field of a request, checked against the names the API allows where it names a resource.
   */
  private static String requireName(ObjectNode request, String field) {
    String value = request.path(field).asString(null);
    if (value == null || value.isBlank()) {
      throw S3TablesException.missingField(field);
    }
    if (("name".equals(field) || "newName".equals(field)) && !NAME.matcher(value).matches()) {
      throw S3TablesException.badRequest("The name '" + value + "' is not a valid name of the S3 Tables API, which"
          + " takes lower-case letters, digits, hyphens and underscores.");
    }
    return value;
  }

  /**
   * A required object field of a request, as the JSON that is stored for it.
   */
  private static String requireObject(ObjectNode request, String field) {
    JsonNode value = request.get(field);
    if (value == null || !value.isObject()) {
      throw S3TablesException.missingField(field);
    }
    return IcebergJson.write(value);
  }

  /**
   * The single name of the {@code namespace} of a request, which the API carries as a list of one: Amazon S3 Tables
   * serves one level of namespace, whatever the shape of the field suggests.
   */
  private static String requireNamespaceOf(ObjectNode request) {
    JsonNode namespace = request.get("namespace");
    if (namespace == null || !namespace.isArray() || namespace.isEmpty()) {
      throw S3TablesException.missingField("namespace");
    }
    if (namespace.size() > 1) {
      throw S3TablesException.badRequest("The S3 Tables API serves one level of namespace; got "
          + namespace.size() + ".");
    }
    String name = namespace.get(0).asString();
    if (name == null || name.isBlank() || !NAME.matcher(name).matches()) {
      throw S3TablesException.badRequest("The namespace '" + name + "' is not a valid namespace of the S3 Tables"
          + " API, which takes lower-case letters, digits, hyphens and underscores.");
    }
    return name;
  }

  private static void requireIcebergFormat(String format) {
    if (!FORMAT_ICEBERG.equalsIgnoreCase(format)) {
      throw S3TablesException.invalidValue("format", format, List.of(FORMAT_ICEBERG));
    }
  }

  /**
   * A table and the table bucket it is in, which every operation on a table starts by finding.
   */
  private record Located(TableBucketRecord bucket, TableAttributes table) {
  }

}
