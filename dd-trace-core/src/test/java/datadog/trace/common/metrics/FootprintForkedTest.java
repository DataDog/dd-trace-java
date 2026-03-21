package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.api.WellKnownTags;
import datadog.trace.core.monitor.HealthMetrics;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.openjdk.jol.info.GraphLayout;

class FootprintForkedTest {

  private static Random random = new Random(0);

  @BeforeAll
  static void setupSpec() {
    assumeFalse(
        System.getProperty("java.vendor", "").toUpperCase().contains("IBM"), "Skipping on IBM JVM");
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  static Stream<Arguments> footprintLessThan10MBArguments() {
    return Stream.of(
        Arguments.of(5, 1, 10, 2, 0.00),
        Arguments.of(5, 1, 100, 2, 0.00),
        Arguments.of(5, 1, 10, 2, 0.01),
        Arguments.of(5, 1, 100, 2, 0.01),
        Arguments.of(10, 1, 100, 2, 0.00),
        Arguments.of(10, 1, 100, 2, 0.01));
  }

  @ParameterizedTest
  @MethodSource("footprintLessThan10MBArguments")
  void footprintLessThan10MB(
      int operationCardinality,
      int servicePerOperation,
      int resourceNamesPerService,
      int typesPerOperation,
      double errorRate)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    ValidatingSink sink = new ValidatingSink(latch);
    DDAgentFeaturesDiscovery features = Mockito.mock(DDAgentFeaturesDiscovery.class);
    Mockito.when(features.supportsMetrics()).thenReturn(true);
    Mockito.when(features.peerTags()).thenReturn(Collections.emptySet());
    ConflatingMetricsAggregator aggregator =
        new ConflatingMetricsAggregator(
            new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language"),
            Collections.emptySet(),
            features,
            HealthMetrics.NO_OP,
            sink,
            1000,
            1000,
            100,
            SECONDS,
            false);
    long baseline = footprint(aggregator, features);
    aggregator.start();

    String[] operations = randomNames(operationCardinality);
    Map<String, String[]> serviceNamesByOperation =
        scopedRandomNames(operations, servicePerOperation);
    Map<String, String[]> resourceNamesByService =
        scopedRandomNamesFromArrays(
            serviceNamesByOperation.values().toArray(new String[0][]), resourceNamesPerService);
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
      assertTrue(attempts < 10);
    }
    assertTrue(latch.await(30, SECONDS));

    long after = footprint(aggregator, features);
    assertTrue(
        after - baseline <= 10L * 1024 * 1024, "Footprint exceeded 10MB: " + (after - baseline));

    aggregator.close();
  }

  private String[] randomNames(int cardinality) {
    String[] things = new String[cardinality];
    for (int i = 0; i < things.length; ++i) {
      things[i] = UUID.randomUUID().toString();
    }
    return things;
  }

  private Map<String, String[]> scopedRandomNames(String[] parents, int childCardinality) {
    Map<String, String[]> things = new HashMap<>();
    for (String parent : parents) {
      things.put(parent, randomNames(childCardinality));
    }
    return things;
  }

  private Map<String, String[]> scopedRandomNamesFromArrays(
      String[][] parents, int childCardinality) {
    Map<String, String[]> things = new HashMap<>();
    for (String[] parent : parents) {
      for (String p : parent) {
        things.put(p, randomNames(childCardinality));
      }
    }
    return things;
  }

  private long expDistributedNanoseconds(double intensity) {
    return (long) (Math.log(random.nextDouble()) / Math.log(1 - intensity) + 1);
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
}
