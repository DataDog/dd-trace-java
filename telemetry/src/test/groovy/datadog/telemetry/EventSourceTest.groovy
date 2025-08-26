package datadog.telemetry

import datadog.telemetry.api.DistributionSeries
import datadog.telemetry.api.Integration
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.Metric
import datadog.telemetry.dependency.Dependency
import ConfigOrigin
import ConfigSetting
import datadog.trace.api.telemetry.Endpoint
import datadog.trace.api.telemetry.ProductChange
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.LinkedBlockingQueue

class EventSourceTest extends DDSpecification{

  void "test isEmpty when adding and clearing #eventType"() {
    setup:
    final eventQueues = [
      configChangeQueue      : new LinkedBlockingQueue<ConfigSetting>(),
      integrationQueue       : new LinkedBlockingQueue<Integration>(),
      dependencyQueue        : new LinkedBlockingQueue<Dependency>(),
      metricQueue            : new LinkedBlockingQueue<Metric>(),
      distributionSeriesQueue: new LinkedBlockingQueue<DistributionSeries>(),
      logMessageQueue        : new LinkedBlockingQueue<LogMessage>(),
      productChanges         : new LinkedBlockingQueue<ProductChange>(),
      endpointQueue          : new LinkedBlockingQueue<Endpoint>()
    ]

    def eventSource = new EventSource.Queued(
      eventQueues.configChangeQueue,
      eventQueues.integrationQueue,
      eventQueues.dependencyQueue,
      eventQueues.metricQueue,
      eventQueues.distributionSeriesQueue,
      eventQueues.logMessageQueue,
      eventQueues.productChanges,
      eventQueues.endpointQueue
      )

    expect:
    eventSource.isEmpty()

    when: "add an event to the queue"
    eventQueues[eventQueueName].add(eventInstance)

    then: "eventSource should not be empty"
    !eventSource.isEmpty()

    when: "clear the queue"
    eventQueues[eventQueueName].clear()

    then: "eventSource should be empty again"
    eventSource.isEmpty()

    where:
    eventType             | eventQueueName            | eventInstance
    "Config Change"       | "configChangeQueue"       | new ConfigSetting("key", "value", ConfigOrigin.ENV)
    "Integration"         | "integrationQueue"        | new Integration("name", true)
    "Dependency"          | "dependencyQueue"         | new Dependency("name", "version", "type", null)
    "Metric"              | "metricQueue"             | new Metric()
    "Distribution Series" | "distributionSeriesQueue" | new DistributionSeries()
    "Log Message"         | "logMessageQueue"         | new LogMessage()
    "Product Change"      | "productChanges"          | new ProductChange()
    "Endpoint"            | "endpointQueue"           | new Endpoint()
  }
}
