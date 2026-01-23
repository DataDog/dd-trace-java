package datadog.trace.common.metrics

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.metrics.impl.DDSketchHistograms
import datadog.trace.api.WellKnownTags
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.test.util.DDSpecification
import org.openjdk.jol.info.GraphLayout
import spock.lang.Requires
import spock.lang.Shared

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom

import static java.util.concurrent.TimeUnit.SECONDS

@Requires({
  !System.getProperty("java.vendor").toUpperCase().contains("IBM")
})
class FootprintForkedTest extends DDSpecification {

  @Shared
  Random random = new Random(0)

  def "footprint less than 10MB"() {
    setup:
    // Initialize metrics-lib histograms to register the DDSketch implementation
    DDSketchHistograms.histograms()

    CountDownLatch latch = new CountDownLatch(1)
    ValidatingSink sink = new ValidatingSink(latch)
    DDAgentFeaturesDiscovery features = Stub(DDAgentFeaturesDiscovery) {
      it.supportsMetrics() >> true
      it.peerTags() >> []
    }
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(
      new WellKnownTags("runtimeid","hostname", "env", "service", "version","language"),
      [].toSet() as Set<String>,
      features,
      HealthMetrics.NO_OP,
      sink,
      1000,
      1000,
      100,
      SECONDS)
    // Removing the 'features' as it's a mock, and mocks are heavyweight, e.g. around 22MiB
    def baseline = footprint(aggregator, features)
    aggregator.start()

    when: "lots of traces are published"
    String[] operations = randomNames(operationCardinality)
    Map<String, String[]> serviceNamesByOperation = scopedRandomNames(operations, servicePerOperation)
    Map<String, String[]> resourceNamesByService = scopedRandomNames(serviceNamesByOperation.values(), resourceNamesPerService)
    Map<String, String[]> typesByOperation = scopedRandomNames(operations, typesPerOperation)
    int traceCount = 10_000
    int errorThreshold = (int) (errorRate * traceCount)
    for (int i = 0; i < traceCount; ++i) {
      String operation = operations[ThreadLocalRandom.current().nextInt(operations.length)]
      String[] types = typesByOperation.get(operation)
      String type = types[ThreadLocalRandom.current().nextInt(types.length)]
      String[] serviceNames = serviceNamesByOperation.get(operation)
      String serviceName = serviceNames[ThreadLocalRandom.current().nextInt(serviceNames.length)]
      String[] resourceNames = resourceNamesByService.get(serviceName)
      String resourceName = resourceNames[ThreadLocalRandom.current().nextInt(resourceNames.length)]
      boolean isError = ThreadLocalRandom.current().nextInt(traceCount) < errorThreshold
      aggregator.publish([
        new SimpleSpan(serviceName, operation, resourceName, type, true, true, isError, System.nanoTime(),
        isError ? expDistributedNanoseconds(0.99) : expDistributedNanoseconds(0.01), 200)
      ])
    }
    if (!aggregator.report()) {
      int attempts = 0
      while (++attempts < 10 && !aggregator.report()) {
        Thread.sleep(10)
      }
      assert attempts < 10
    }
    assert latch.await(30, SECONDS)

    then:
    def after = footprint(aggregator, features)
    after - baseline <= 10 * 1024 * 1024

    cleanup:
    aggregator.close()

    where:
    operationCardinality | servicePerOperation | resourceNamesPerService | typesPerOperation | errorRate
    5                    |  1                  |  10                     |  2                |   0.00
    5                    |  1                  |  100                    |  2                |   0.00
    5                    |  1                  |  10                     |  2                |   0.01
    5                    |  1                  |  100                    |  2                |   0.01
    10                   |  1                  |  100                    |  2                |   0.00
    10                   |  1                  |  100                    |  2                |   0.01
  }

  def randomNames(int cardinality) {
    String[] things = new String[cardinality]
    for (int i = 0; i < things.length; ++i) {
      things[i] = UUID.randomUUID().toString()
    }
    return things
  }

  def scopedRandomNames(String[] parents, int childCardinality) {
    Map<String, String[]> things = new HashMap<>()
    for (String parent : parents) {
      things.put(parent, randomNames(childCardinality))
    }
    return things
  }

  def scopedRandomNames(Collection<String[]> parents, int childCardinality) {
    Map<String, String[]> things = new HashMap<>()
    for (String[] parent : parents) {
      for (String p : parent) {
        things.put(p, randomNames(childCardinality))
      }
    }
    return things
  }

  def expDistributedNanoseconds(double intensity) {
    return (long)(Math.log(random.nextDouble()) / Math.log(1 - intensity) + 1)
  }

  class ValidatingSink implements Sink {

    final CountDownLatch latch

    ValidatingSink(CountDownLatch latch) {
      this.latch = latch
    }

    @Override
    void register(EventListener listener) {
    }

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      latch.countDown()
    }
  }

  static long footprint(Object root, Object... excludedRootFieldInstance) {
    GraphLayout layout = GraphLayout.parseInstance(root)
    def size = layout.totalSize()

    excludedRootFieldInstance.each {
      def excludedLayout = GraphLayout.parseInstance(it)
      layout = layout.subtract(excludedLayout)
      size -= excludedLayout.totalSize()
    }

    println(layout.toFootprint())
    return size
  }
}
