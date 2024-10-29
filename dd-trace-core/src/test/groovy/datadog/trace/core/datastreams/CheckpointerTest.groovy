package datadog.trace.core.datastreams

import datadog.trace.api.experimental.DataStreamsContextCarrier
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED

class CheckpointerTest extends DDCoreSpecification {

  void 'test setting produce & consume checkpoint'() {
    setup:
    // Enable DSM
    injectSysConfig(DATA_STREAMS_ENABLED, 'true')
    // Create a test tracer
    def tracer = tracerBuilder().build()
    AgentTracer.forceRegister(tracer)
    // Get the test checkpointer
    def checkpointer = tracer.getDataStreamsCheckpointer()
    // Declare the carrier to test injected data
    def carrier = new CustomContextCarrier()
    // Start and activate a span
    def span = tracer.buildSpan('test', 'dsm-checkpoint').start()
    def scope = tracer.activateSpan(span)

    when:
    // Trigger produce checkpoint
    checkpointer.setProduceCheckpoint('kafka', 'testTopic', carrier)
    checkpointer.setConsumeCheckpoint('kafka', 'testTopic', carrier)
    // Clean up span
    scope.close()
    span.finish()

    then:
    carrier.entries().any { entry -> entry.getKey() == "dd-pathway-ctx-base64" }
    span.context().pathwayContext.hash != 0
  }

  void 'test transaction tracking'() {
    setup:
    // Enable DSM
    injectSysConfig(DATA_STREAMS_ENABLED, 'true')
    // Create a test tracer
    def tracer = tracerBuilder().build()
    AgentTracer.forceRegister(tracer)
    // Get the test checkpointer
    def checkpointer = tracer.getDataStreamsCheckpointer()
    // Declare the carrier to test injected data
    def carrier = new CustomContextCarrier()
    // Start and activate a span
    def span = tracer.buildSpan('test', 'transaction-tracking').start()
    def scope = tracer.activateSpan(span)
    // Generate a unique transaction ID
    def transactionID = UUID.randomUUID().toString()

    when:
    // Track the transaction
    checkpointer.trackTransaction(transactionID, carrier)
    // Clean up span
    scope.close()
    span.finish()

    then:
    // Verify that the transaction ID tag is set on the span
    span.context().getTag("transaction.id") == transactionID

    // Verify that the transaction ID is set in the carrier for propagation
    carrier.entries().any { entry -> entry.getKey() == "transaction.id" && entry.getValue() == transactionID }

    // Verify that the pathway context is initialized and injected
    span.context().pathwayContext != null
    span.context().pathwayContext.isStarted()

    // Verify that the pathway context hash is set
    span.context().pathwayContext.getHash() != 0
  }

  class CustomContextCarrier implements DataStreamsContextCarrier {

    private Map<String, Object> data = new HashMap<>()

    @Override
    Set<Map.Entry<String, Object>> entries() {
      return data.entrySet()
    }

    @Override
    void set(String key, String value) {
      data.put(key, value)
    }
  }
}
