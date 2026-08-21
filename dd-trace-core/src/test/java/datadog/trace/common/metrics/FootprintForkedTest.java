package datadog.trace.common.metrics;

import static java.util.Collections.emptySet;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.api.WellKnownTags;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.test.util.Flaky;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.openjdk.jol.info.GraphLayout;
import org.tabletest.junit.TableTest;

@DisabledIfSystemProperty(named = "java.vendor", matches = "(?i).*IBM.*")
class FootprintForkedTest {

  private static final Random RANDOM = new Random(0);

  @BeforeAll
  static void setupSpec() {
    // Initialize metrics-lib histograms to register the DDSketch implementation
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  @TableTest({
    "scenario                       | operationCardinality | servicePerOperation | resourceNamesPerService | typesPerOperation | errorRate",
    "5 ops 10 resources 0% errors   | 5                    | 1                   | 10                      | 2                 | 0.00     ",
    "5 ops 100 resources 0% errors  | 5                    | 1                   | 100                     | 2                 | 0.00     ",
    "5 ops 10 resources 1% errors   | 5                    | 1                   | 10                      | 2                 | 0.01     ",
    "5 ops 100 resources 1% errors  | 5                    | 1                   | 100                     | 2                 | 0.01     ",
    "10 ops 100 resources 0% errors | 10                   | 1                   | 100                     | 2                 | 0.00     ",
    "10 ops 100 resources 1% errors | 10                   | 1                   | 100                     | 2                 | 0.01     "
  })
  @Flaky(
      "High-cardinality cases occasionally exceed the 10MB footprint growth threshold on some environments/JVMs")
  void footprintLessThan10MB(
      int operationCardinality,
      int servicePerOperation,
      int resourceNamesPerService,
      int typesPerOperation,
      double errorRate)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    ValidatingSink sink = new ValidatingSink(latch);
    DDAgentFeaturesDiscovery features =
        mock(DDAgentFeaturesDiscovery.class, withSettings().stubOnly());
    when(features.supportsMetrics()).thenReturn(true);
    when(features.peerTags()).thenReturn(emptySet());
    ClientStatsAggregator aggregator =
        new ClientStatsAggregator(
            new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language"),
            emptySet(),
            AdditionalTagsSchema.EMPTY,
            features,
            HealthMetrics.NO_OP,
            sink,
            1000,
            1000,
            100,
            SECONDS,
            false);
    // Removing the 'features' as it's a mock, and mocks are heavyweight, e.g. around 22MiB
    long baseline = footprint(aggregator, features);
    aggregator.start();
    try {

      // lots of traces are published
      String[] operations = randomNames(operationCardinality);
      Map<String, String[]> serviceNamesByOperation =
          scopedRandomNames(operations, servicePerOperation);
      Map<String, String[]> resourceNamesByService =
          scopedRandomNames(serviceNamesByOperation.values(), resourceNamesPerService);
      Map<String, String[]> typesByOperation = scopedRandomNames(operations, typesPerOperation);
      int traceCount = 10_000;
      int errorThreshold = (int) (errorRate * traceCount);
      for (int i = 0; i < traceCount; ++i) {
        String operation = operations[ThreadLocalRandom.current().nextInt(operations.length)];
        String[] types = typesByOperation.get(operation);
        String type = types[ThreadLocalRandom.current().nextInt(types.length)];
        String[] serviceNames = serviceNamesByOperation.get(operation);
        String serviceName = serviceNames[ThreadLocalRandom.current().nextInt(serviceNames.length)];
        String[] resourceNames = resourceNamesByService.get(serviceName);
        String resourceName =
            resourceNames[ThreadLocalRandom.current().nextInt(resourceNames.length)];
        boolean isError = ThreadLocalRandom.current().nextInt(traceCount) < errorThreshold;
        aggregator.publish(
            Collections.singletonList(
                new SimpleSpan(
                    serviceName,
                    operation,
                    resourceName,
                    type,
                    true,
                    true,
                    isError,
                    System.nanoTime(),
                    isError ? expDistributedNanoseconds(0.99) : expDistributedNanoseconds(0.01),
                    200)));
      }
      if (!aggregator.report()) {
        int attempts = 0;
        while (++attempts < 10 && !aggregator.report()) {
          Thread.sleep(10);
        }
        assertTrue(attempts < 10, "aggregator failed to report within 10 attempts");
      }
      assertTrue(latch.await(30, SECONDS), "latch was not triggered within 30 seconds");
      long after = footprint(aggregator, features);
      assertTrue(after - baseline <= 10L * 1024 * 1024, "footprint growth exceeds 10MB");
    } finally {
      aggregator.close();
    }
  }

  private static String[] randomNames(int cardinality) {
    String[] things = new String[cardinality];
    for (int i = 0; i < things.length; ++i) {
      things[i] = UUID.randomUUID().toString();
    }
    return things;
  }

  private static Map<String, String[]> scopedRandomNames(String[] parents, int childCardinality) {
    Map<String, String[]> things = new HashMap<>();
    for (String parent : parents) {
      things.put(parent, randomNames(childCardinality));
    }
    return things;
  }

  private static Map<String, String[]> scopedRandomNames(
      Collection<String[]> parents, int childCardinality) {
    Map<String, String[]> things = new HashMap<>();
    for (String[] parent : parents) {
      for (String p : parent) {
        things.put(p, randomNames(childCardinality));
      }
    }
    return things;
  }

  private static long expDistributedNanoseconds(double intensity) {
    return (long) (Math.log(RANDOM.nextDouble()) / Math.log(1 - intensity) + 1);
  }

  private static long footprint(Object root, Object... excludedRootFieldInstances) {
    GraphLayout layout = GraphLayout.parseInstance(root);
    long size = layout.totalSize();
    for (Object excluded : excludedRootFieldInstances) {
      GraphLayout excludedLayout = GraphLayout.parseInstance(excluded);
      layout = layout.subtract(excludedLayout);
      size -= excludedLayout.totalSize();
    }
    System.out.println(layout.toFootprint());
    return size;
  }

  private static class ValidatingSink implements Sink {

    final CountDownLatch latch;

    ValidatingSink(CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public void register(EventListener listener) {}

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      latch.countDown();
    }
  }
}
