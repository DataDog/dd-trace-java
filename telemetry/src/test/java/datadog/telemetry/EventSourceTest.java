package datadog.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.ProductChange;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EventSourceTest {

  private static class EventQueues {
    final Queue<ConfigSetting> configChangeQueue = new LinkedBlockingQueue<>();
    final Queue<Integration> integrationQueue = new LinkedBlockingQueue<>();
    final Queue<Dependency> dependencyQueue = new LinkedBlockingQueue<>();
    final Queue<Metric> metricQueue = new LinkedBlockingQueue<>();
    final Queue<DistributionSeries> distributionSeriesQueue = new LinkedBlockingQueue<>();
    final Queue<LogMessage> logMessageQueue = new LinkedBlockingQueue<>();
    final Queue<ProductChange> productChanges = new LinkedBlockingQueue<>();
    final Queue<Endpoint> endpointQueue = new LinkedBlockingQueue<>();
  }

  @SuppressWarnings("unchecked")
  private static <T> void addInstance(Queue<T> queue, Object instance) {
    queue.add((T) instance);
  }

  @ParameterizedTest(name = "test isEmpty when adding and clearing {0}")
  @MethodSource("testIsEmptyWhenAddingAndClearingArguments")
  void testIsEmptyWhenAddingAndClearing(
      String eventType, Function<EventQueues, Queue<?>> queueSelector, Object eventInstance) {
    EventQueues eventQueues = new EventQueues();
    EventSource eventSource =
        new EventSource.Queued(
            eventQueues.configChangeQueue,
            eventQueues.integrationQueue,
            eventQueues.dependencyQueue,
            eventQueues.metricQueue,
            eventQueues.distributionSeriesQueue,
            eventQueues.logMessageQueue,
            eventQueues.productChanges,
            eventQueues.endpointQueue);

    assertTrue(eventSource.isEmpty());

    // add an event to the queue
    Queue<?> queue = queueSelector.apply(eventQueues);
    addInstance(queue, eventInstance);

    // eventSource should not be empty
    assertFalse(eventSource.isEmpty());

    // clear the queue
    queue.clear();

    // eventSource should be empty again
    assertTrue(eventSource.isEmpty());
  }

  private static Stream<Arguments> testIsEmptyWhenAddingAndClearingArguments() {
    return Stream.of(
        arguments(
            "Config Change",
            (Function<EventQueues, Queue<?>>) queues -> queues.configChangeQueue,
            ConfigSetting.of("key", "value", ConfigOrigin.ENV)),
        arguments(
            "Integration",
            (Function<EventQueues, Queue<?>>) queues -> queues.integrationQueue,
            new Integration("name", true)),
        arguments(
            "Dependency",
            (Function<EventQueues, Queue<?>>) queues -> queues.dependencyQueue,
            new Dependency("name", "version", "type", null)),
        arguments(
            "Metric", (Function<EventQueues, Queue<?>>) queues -> queues.metricQueue, new Metric()),
        arguments(
            "Distribution Series",
            (Function<EventQueues, Queue<?>>) queues -> queues.distributionSeriesQueue,
            new DistributionSeries()),
        arguments(
            "Log Message",
            (Function<EventQueues, Queue<?>>) queues -> queues.logMessageQueue,
            new LogMessage()),
        arguments(
            "Product Change",
            (Function<EventQueues, Queue<?>>) queues -> queues.productChanges,
            new ProductChange()),
        arguments(
            "Endpoint",
            (Function<EventQueues, Queue<?>>) queues -> queues.endpointQueue,
            new Endpoint()));
  }
}
