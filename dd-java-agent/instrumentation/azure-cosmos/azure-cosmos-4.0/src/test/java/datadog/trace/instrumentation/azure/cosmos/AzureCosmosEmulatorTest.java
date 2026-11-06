package datadog.trace.instrumentation.azure.cosmos;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import datadog.context.ContextScope;
import datadog.environment.OperatingSystem;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.CosmosDBEmulatorContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the reference scenario of the dd-trace-py Cosmos integration against the Cosmos DB emulator,
 * with the sync and the async client.
 *
 * <p>Only gateway mode is covered: the emulator container exposes the gateway port, not the ports
 * direct mode connects to.
 */
class AzureCosmosEmulatorTest extends AbstractInstrumentationTest {
  private static final String CONTAINER = "container";
  private static final String OPERATION_NAME = "cosmosdb.query";
  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

  @TempDir static Path tempDir;

  private static CosmosDBEmulatorContainer emulator;

  @BeforeAll
  static void startEmulator() throws Exception {
    // The Linux emulator image has no arm64 build.
    assumeFalse(
        OperatingSystem.architecture().isArm64(), "Cosmos DB emulator requires an amd64 host");
    emulator =
        new CosmosDBEmulatorContainer(
                DockerImageName.parse(System.getProperty("test.cosmosdb.image"))
                    .asCompatibleSubstituteFor(
                        "mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator"))
            .withEnv("AZURE_COSMOS_EMULATOR_PARTITION_COUNT", "2")
            .withEnv("AZURE_COSMOS_EMULATOR_ENABLE_DATA_PERSISTENCE", "false")
            .withStartupTimeout(Duration.ofMinutes(5));
    emulator.start();

    // the emulator serves a self-signed certificate
    Path keyStoreFile = tempDir.resolve("azure-cosmos-emulator.keystore");
    KeyStore keyStore = emulator.buildNewKeyStore();
    try (OutputStream out = Files.newOutputStream(keyStoreFile)) {
      keyStore.store(out, emulator.getEmulatorKey().toCharArray());
    }
    System.setProperty("javax.net.ssl.trustStore", keyStoreFile.toString());
    System.setProperty("javax.net.ssl.trustStorePassword", emulator.getEmulatorKey());
    System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
  }

  @AfterAll
  static void stopEmulator() {
    if (emulator != null) {
      emulator.stop();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"sync", "async"})
  void crudScenario(String api) {
    String db = newDatabaseName(api);
    String coll = "/dbs/" + db + "/colls/" + CONTAINER;
    long parentId;
    try (TestClient client = client(api)) {
      parentId =
          runUnderParent(
              () -> {
                client.createDatabase(db);
                client.createContainer(db);
                client.createItem(db, new Item("item1", "created"));
                client.readItem(db, "item1");
                client.upsertItem(db, new Item("item1", "upserted"));
                client.queryItems(db);
                client.deleteItem(db, "item1");
                client.deleteDatabase(db);
              });
    }

    List<DDSpan> cosmosSpans = cosmosSpans(traceOf(parentId));
    List<DDSpan> scenario =
        findInOrder(
            cosmosSpans,
            exact("Create /dbs"),
            exact("Create /dbs/" + db + "/colls"),
            exact("Create " + coll + "/docs"),
            exact("Read " + coll + "/docs/?"),
            exact("Upsert " + coll + "/docs"),
            // the Java SDK names the operation of SQL queries Query, dd-trace-py sees SqlQuery
            Pattern.compile("(Sql)?Query " + Pattern.quote(coll + "/docs")),
            exact("Delete " + coll + "/docs/?"),
            exact("Delete /dbs/" + db));

    // status codes of: create db, create container, create, read, upsert (existing), query, delete
    // item, delete db
    String[] statusCodes = {"201", "201", "201", "200", "200", "200", "204", "204"};
    for (int i = 0; i < scenario.size(); i++) {
      DDSpan span = scenario.get(i);
      assertEquals(parentId, span.getParentId(), "parent of " + resource(span));
      assertEquals(
          statusCodes[i],
          span.getTag(AzureCosmosDecorator.STATUS_CODE),
          "status of " + resource(span));
    }
    for (DDSpan span : cosmosSpans) {
      assertCommonTags(span, db);
      if (resource(span).contains(coll)) {
        assertEquals(CONTAINER, span.getTag(AzureCosmosDecorator.CONTAINER), resource(span));
      } else {
        assertNull(span.getTag(AzureCosmosDecorator.CONTAINER), resource(span));
      }
      assertTrue(!span.isError(), "unexpected error on " + resource(span));
      Object statusCode = span.getTag(AzureCosmosDecorator.STATUS_CODE);
      assertTrue(
          statusCode != null && statusCode.toString().startsWith("2"),
          "status of " + resource(span) + ": " + statusCode);
    }
    assertNoDatabaseAccountSpans();
  }

  @ParameterizedTest
  @ValueSource(strings = {"sync", "async"})
  void conflictIsTaggedAsError(String api) {
    String db = newDatabaseName(api);
    long parentId;
    try (TestClient client = client(api)) {
      client.createDatabase(db);
      try {
        client.createContainer(db);
        client.upsertItem(db, new Item("item1", "upserted"));
        parentId =
            runUnderParent(
                () -> {
                  CosmosException thrown =
                      assertThrows(
                          CosmosException.class,
                          () -> client.createItem(db, new Item("item1", "duplicate")));
                  assertEquals(409, thrown.getStatusCode());
                });
      } finally {
        client.deleteDatabase(db);
      }
    }

    List<DDSpan> spans =
        findInOrder(
            cosmosSpans(traceOf(parentId)),
            exact("Create /dbs/" + db + "/colls/" + CONTAINER + "/docs"));
    DDSpan span = spans.get(0);
    assertCommonTags(span, db);
    assertAll(
        () -> assertEquals(parentId, span.getParentId()),
        () -> assertTrue(span.isError(), "span should be marked as error"),
        () -> assertEquals("409", span.getTag(AzureCosmosDecorator.STATUS_CODE)),
        () -> assertNull(span.getTag(AzureCosmosDecorator.SUB_STATUS_CODE)),
        () -> assertNotNull(span.getTag(DDTags.ERROR_MSG)),
        () -> {
          Object errorType = span.getTag(DDTags.ERROR_TYPE);
          assertNotNull(errorType);
          Class<?> type =
              Class.forName(errorType.toString(), false, CosmosException.class.getClassLoader());
          assertTrue(CosmosException.class.isAssignableFrom(type), errorType.toString());
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"sync", "async"})
  void noNestedHttpClientSpans(String api) {
    String db = newDatabaseName(api);
    long parentId;
    try (TestClient client = client(api)) {
      parentId =
          runUnderParent(
              () -> {
                client.createDatabase(db);
                client.deleteDatabase(db);
              });
    }

    List<String> others = new ArrayList<>();
    for (DDSpan span : traceOf(parentId)) {
      if (span.getSpanId() != parentId && !OPERATION_NAME.contentEquals(span.getOperationName())) {
        others.add(span.getOperationName() + " " + span.getResourceName());
      }
    }
    assertTrue(others.isEmpty(), "spans besides Cosmos request spans: " + others);
  }

  private static void assertCommonTags(DDSpan span, String db) {
    String resource = resource(span);
    assertAll(
        resource,
        () -> assertEquals("cosmosdb", span.getSpanType()),
        () -> assertEquals("azure-cosmos", String.valueOf(span.getTag(Tags.COMPONENT))),
        () -> assertEquals(Tags.SPAN_KIND_CLIENT, span.getTag(Tags.SPAN_KIND)),
        () -> assertEquals("cosmosdb", span.getTag(Tags.DB_TYPE)),
        () -> assertEquals(db, span.getTag(Tags.DB_INSTANCE)),
        () -> assertEquals(emulator.getHost(), span.getTag(Tags.PEER_HOSTNAME)),
        () -> assertEquals("gateway", span.getTag(AzureCosmosDecorator.CONNECTION_MODE)),
        () -> {
          Object userAgent = span.getTag(Tags.HTTP_USER_AGENT);
          assertNotNull(userAgent);
          // early 4.x versions identify as cosmosdb-java-sdk
          assertTrue(
              userAgent.toString().startsWith("azsdk-java-cosmos/")
                  || userAgent.toString().startsWith("cosmosdb-java-sdk/"),
              userAgent.toString());
        });
  }

  /** The SDK's own account discovery requests ({@code DatabaseAccount}) must not be traced. */
  private void assertNoDatabaseAccountSpans() {
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : cosmosSpans(trace)) {
        String resource = resource(span);
        String path = resource.substring(resource.indexOf(' ') + 1);
        assertTrue(path.startsWith("/dbs"), "unexpected Cosmos request span: " + resource);
      }
    }
  }

  /** Runs the body under a new parent span and returns the id of that span. */
  private static long runUnderParent(Runnable body) {
    AgentSpan parent = startSpan("test", "parent");
    try (ContextScope ignored = activateSpan(parent)) {
      body.run();
    } finally {
      parent.finish();
    }
    return parent.getSpanId();
  }

  private List<DDSpan> traceOf(long parentId) {
    blockUntilTracesMatch(traces -> findTrace(traces, parentId) != null);
    return findTrace(writer, parentId);
  }

  private static List<DDSpan> findTrace(Iterable<List<DDSpan>> traces, long spanId) {
    for (List<DDSpan> trace : traces) {
      for (DDSpan span : trace) {
        if (span.getSpanId() == spanId) {
          return trace;
        }
      }
    }
    return null;
  }

  private static List<DDSpan> cosmosSpans(List<DDSpan> trace) {
    return trace.stream()
        .filter(span -> OPERATION_NAME.contentEquals(span.getOperationName()))
        .sorted((a, b) -> Long.compare(a.getStartTime(), b.getStartTime()))
        .collect(toList());
  }

  /**
   * Returns the first span matching each resource pattern, each one starting after the previous
   * match. The SDK sends extra metadata requests (container reads, partition key ranges) between
   * the scenario requests, so the scenario is checked as an ordered subsequence.
   */
  private static List<DDSpan> findInOrder(List<DDSpan> spans, Pattern... resources) {
    List<DDSpan> found = new ArrayList<>();
    int next = 0;
    for (Pattern expected : resources) {
      DDSpan match = null;
      while (next < spans.size() && match == null) {
        DDSpan candidate = spans.get(next++);
        if (expected.matcher(resource(candidate)).matches()) {
          match = candidate;
        }
      }
      if (match == null) {
        fail(
            "no span with resource "
                + expected
                + " in order, spans were: "
                + spans.stream().map(AzureCosmosEmulatorTest::resource).collect(toList()));
      }
      found.add(match);
    }
    return found;
  }

  private static Pattern exact(String resource) {
    return Pattern.compile(Pattern.quote(resource));
  }

  /** The span resource, without a trailing slash, which the SDK may or may not send. */
  private static String resource(DDSpan span) {
    String resource = String.valueOf(span.getResourceName());
    return resource.endsWith("/") ? resource.substring(0, resource.length() - 1) : resource;
  }

  private static String newDatabaseName(String api) {
    return "db-" + api + "-" + DATABASE_COUNTER.incrementAndGet();
  }

  private static CosmosClientBuilder clientBuilder() {
    return new CosmosClientBuilder()
        .endpoint(emulator.getEmulatorEndpoint())
        .key(emulator.getEmulatorKey())
        .gatewayMode()
        .endpointDiscoveryEnabled(false)
        .consistencyLevel(ConsistencyLevel.SESSION);
  }

  private static TestClient client(String api) {
    return "async".equals(api)
        ? new AsyncTestClient(clientBuilder().buildAsyncClient())
        : new SyncTestClient(clientBuilder().buildClient());
  }

  /** A document stored by the tests. */
  public static class Item {
    public String id;
    public String value;

    public Item() {}

    Item(String id, String value) {
      this.id = id;
      this.value = value;
    }
  }

  /** The scenario steps, run with either the sync or the async Cosmos client. */
  private interface TestClient extends AutoCloseable {
    void createDatabase(String db);

    void createContainer(String db);

    void createItem(String db, Item item);

    void readItem(String db, String id);

    void upsertItem(String db, Item item);

    void queryItems(String db);

    void deleteItem(String db, String id);

    void deleteDatabase(String db);

    @Override
    void close();
  }

  private static final class SyncTestClient implements TestClient {
    private final CosmosClient client;

    SyncTestClient(CosmosClient client) {
      this.client = client;
    }

    private CosmosContainer container(String db) {
      return client.getDatabase(db).getContainer(CONTAINER);
    }

    @Override
    public void createDatabase(String db) {
      client.createDatabase(db);
    }

    @Override
    public void createContainer(String db) {
      client.getDatabase(db).createContainer(new CosmosContainerProperties(CONTAINER, "/id"));
    }

    @Override
    public void createItem(String db, Item item) {
      container(db).createItem(item);
    }

    @Override
    public void readItem(String db, String id) {
      container(db).readItem(id, new PartitionKey(id), Item.class);
    }

    @Override
    public void upsertItem(String db, Item item) {
      container(db).upsertItem(item);
    }

    @Override
    public void queryItems(String db) {
      container(db)
          .queryItems("SELECT * FROM c", new CosmosQueryRequestOptions(), Item.class)
          .forEach(item -> {});
    }

    @Override
    public void deleteItem(String db, String id) {
      container(db).deleteItem(id, new PartitionKey(id), new CosmosItemRequestOptions());
    }

    @Override
    public void deleteDatabase(String db) {
      client.getDatabase(db).delete();
    }

    @Override
    public void close() {
      client.close();
    }
  }

  private static final class AsyncTestClient implements TestClient {
    private final CosmosAsyncClient client;

    AsyncTestClient(CosmosAsyncClient client) {
      this.client = client;
    }

    private CosmosAsyncContainer container(String db) {
      return client.getDatabase(db).getContainer(CONTAINER);
    }

    @Override
    public void createDatabase(String db) {
      client.createDatabase(db).block();
    }

    @Override
    public void createContainer(String db) {
      client
          .getDatabase(db)
          .createContainer(new CosmosContainerProperties(CONTAINER, "/id"))
          .block();
    }

    @Override
    public void createItem(String db, Item item) {
      container(db).createItem(item).block();
    }

    @Override
    public void readItem(String db, String id) {
      container(db).readItem(id, new PartitionKey(id), Item.class).block();
    }

    @Override
    public void upsertItem(String db, Item item) {
      container(db).upsertItem(item).block();
    }

    @Override
    public void queryItems(String db) {
      container(db)
          .queryItems("SELECT * FROM c", new CosmosQueryRequestOptions(), Item.class)
          .collectList()
          .block();
    }

    @Override
    public void deleteItem(String db, String id) {
      container(db).deleteItem(id, new PartitionKey(id), new CosmosItemRequestOptions()).block();
    }

    @Override
    public void deleteDatabase(String db) {
      client.getDatabase(db).delete().block();
    }

    @Override
    public void close() {
      client.close();
    }
  }
}
