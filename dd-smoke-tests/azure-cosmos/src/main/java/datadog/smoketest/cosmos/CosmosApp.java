package datadog.smoketest.cosmos;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import datadog.trace.api.Trace;

/**
 * Runs a Cosmos DB scenario with the sync and the async client, each under its own root span:
 * create a database, a container and an item, read, upsert and query it, get a 409 conflict and a
 * 404 for a missing item, then delete everything.
 *
 * <p>Settings, as system properties or environment variables:
 *
 * <ul>
 *   <li>{@code cosmos.endpoint} / {@code COSMOS_ENDPOINT}: the account endpoint, required
 *   <li>{@code cosmos.key} / {@code COSMOS_KEY}: the account key, required
 *   <li>{@code cosmos.mode} / {@code COSMOS_MODE}: {@code gateway} (default) or {@code direct}
 *   <li>{@code cosmos.endpoint-discovery} / {@code COSMOS_ENDPOINT_DISCOVERY}: {@code true}
 *       (default) or {@code false}, which the emulator needs
 * </ul>
 *
 * <p>Exits with a non-zero status if a request doesn't behave as expected.
 */
public final class CosmosApp {
  private static final String CONTAINER = "container";
  private static final PartitionKey ITEM_KEY = new PartitionKey("item1");
  private static final PartitionKey MISSING_KEY = new PartitionKey("missing");

  public static void main(String[] args) {
    CosmosClientBuilder builder =
        new CosmosClientBuilder()
            .endpoint(required("cosmos.endpoint", "COSMOS_ENDPOINT"))
            .key(required("cosmos.key", "COSMOS_KEY"))
            .endpointDiscoveryEnabled(
                Boolean.parseBoolean(
                    setting("cosmos.endpoint-discovery", "COSMOS_ENDPOINT_DISCOVERY", "true")))
            .consistencyLevel(ConsistencyLevel.SESSION);
    String mode = setting("cosmos.mode", "COSMOS_MODE", "gateway");
    builder = "direct".equalsIgnoreCase(mode) ? builder.directMode() : builder.gatewayMode();
    String runId = Long.toString(System.currentTimeMillis());

    try (CosmosClient client = builder.buildClient()) {
      runSync(client, "smoke-sync-" + runId);
    }
    try (CosmosAsyncClient client = builder.buildAsyncClient()) {
      runAsync(client, "smoke-async-" + runId);
    }
    System.out.println("Cosmos scenario completed in " + mode + " mode");
    System.exit(0);
  }

  @Trace(operationName = "cosmos.scenario", resourceName = "sync")
  static void runSync(CosmosClient client, String db) {
    client.createDatabase(db);
    CosmosDatabase database = client.getDatabase(db);
    try {
      database.createContainer(new CosmosContainerProperties(CONTAINER, "/id"));
      CosmosContainer container = database.getContainer(CONTAINER);
      container.createItem(new Item("item1", "created"));
      container.readItem("item1", ITEM_KEY, Item.class);
      container.upsertItem(new Item("item1", "upserted"));
      container
          .queryItems("SELECT * FROM c", new CosmosQueryRequestOptions(), Item.class)
          .forEach(item -> {});
      expectStatus(409, () -> container.createItem(new Item("item1", "duplicate")));
      expectStatus(404, () -> container.readItem("missing", MISSING_KEY, Item.class));
      container.deleteItem("item1", ITEM_KEY, new CosmosItemRequestOptions());
    } finally {
      database.delete();
    }
  }

  @Trace(operationName = "cosmos.scenario", resourceName = "async")
  static void runAsync(CosmosAsyncClient client, String db) {
    client.createDatabase(db).block();
    CosmosAsyncDatabase database = client.getDatabase(db);
    try {
      database.createContainer(new CosmosContainerProperties(CONTAINER, "/id")).block();
      CosmosAsyncContainer container = database.getContainer(CONTAINER);
      container.createItem(new Item("item1", "created")).block();
      container.readItem("item1", ITEM_KEY, Item.class).block();
      container.upsertItem(new Item("item1", "upserted")).block();
      container
          .queryItems("SELECT * FROM c", new CosmosQueryRequestOptions(), Item.class)
          .collectList()
          .block();
      expectStatus(409, () -> container.createItem(new Item("item1", "duplicate")).block());
      expectStatus(404, () -> container.readItem("missing", MISSING_KEY, Item.class).block());
      container.deleteItem("item1", ITEM_KEY, new CosmosItemRequestOptions()).block();
    } finally {
      database.delete().block();
    }
  }

  private static void expectStatus(int expected, Runnable request) {
    try {
      request.run();
    } catch (CosmosException e) {
      if (e.getStatusCode() == expected) {
        return;
      }
      throw e;
    }
    throw new IllegalStateException("expected status " + expected + " but the request succeeded");
  }

  private static String required(String property, String variable) {
    String value = setting(property, variable, null);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("set " + property + " or " + variable);
    }
    return value;
  }

  private static String setting(String property, String variable, String defaultValue) {
    String value = System.getProperty(property);
    if (value == null) {
      value = System.getenv(variable);
    }
    return value == null ? defaultValue : value;
  }

  /** A document stored by the scenario. */
  public static class Item {
    public String id;
    public String value;

    public Item() {}

    Item(String id, String value) {
      this.id = id;
      this.value = value;
    }
  }
}
