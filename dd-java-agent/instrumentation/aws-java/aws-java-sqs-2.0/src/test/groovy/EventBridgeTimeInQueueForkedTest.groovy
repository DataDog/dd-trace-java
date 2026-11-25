import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.aws.v2.sqs.TracingList
import software.amazon.awssdk.services.sqs.model.Message

class EventBridgeTimeInQueueForkedTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("sqs.legacy.tracing.enabled", 'false')
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  def "each EventBridge message in a batch gets its own time-in-queue span"() {
    setup:
    TEST_WRITER.clear()
    def sentTimestamp = String.valueOf(System.currentTimeMillis() - 1000)
    def messages = new TracingList([
      eventBridgeMessage(4948377316357291421L, 6746998015037429512L, sentTimestamp),
      eventBridgeMessage(1111222233334444L, 5555666677778888L, sentTimestamp)
    ], "http://localhost/000000000000/somequeue", "00000000-0000-0000-0000-000000000000")

    when:
    messages.each {}
    TEST_WRITER.waitForTraces(2)

    then:
    def tracesById = TEST_WRITER.collectEntries { [(it[0].traceId.toLong()): it] }
    tracesById.keySet() == [4948377316357291421L, 1111222233334444L] as Set
    [
      (4948377316357291421L): 6746998015037429512L,
      (1111222233334444L): 5555666677778888L
    ].each { traceId, producerSpanId ->
      def trace = tracesById[traceId]
      assert trace.size() == 2
      def queueSpan = trace.find { it.resourceName.toString() == "Sqs.DeliverMessage" }
      def consumerSpan = trace.find { it.resourceName.toString() == "Sqs.ReceiveMessage" }
      assert queueSpan.parentId == producerSpanId
      assert consumerSpan.parentId == queueSpan.spanId
    }
  }

  static Message eventBridgeMessage(long traceId, long parentId, String sentTimestamp) {
    Message.builder()
      .body('{"detail-type":"order.created","detail":{"_datadog":' +
      "{\"x-datadog-trace-id\":\"$traceId\",\"x-datadog-parent-id\":\"$parentId\"}}}")
      .attributesWithStrings([SentTimestamp: sentTimestamp])
      .build()
  }
}
