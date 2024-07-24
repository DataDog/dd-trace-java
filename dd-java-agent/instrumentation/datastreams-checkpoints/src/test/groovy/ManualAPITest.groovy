import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.experimental.DataStreamsCheckpointer
import datadog.trace.api.experimental.DataStreamsContextCarrier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ManualAPITest extends AgentTestRunner {
  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  void 'test setting produce checkpoint'() {
    when:
    runUnderTrace("parent") {
      DataStreamsCheckpointer.get().setProduceCheckpoint("kafka", "testTopic", DataStreamsContextCarrier.NoOp.INSTANCE)
    }
    then:
    TEST_DATA_STREAMS_WRITER.waitForPayloads(1)
    then:
    var first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
    verifyAll(first) {
      edgeTags == [
        "direction:out",
        "manual_checkpoint:true",
        "topic:testTopic",
        "type:kafka"
      ]
      edgeTags.size() == 4
    }
  }

  void 'test setting consume checkpoint'() {
    when:
    runUnderTrace("parent") {
      DataStreamsCheckpointer.get().setConsumeCheckpoint("kafka", "testTopic", DataStreamsContextCarrier.NoOp.INSTANCE)
    }
    then:
    TEST_DATA_STREAMS_WRITER.waitForPayloads(1)
    then:
    var first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
    verifyAll(first) {
      edgeTags == [
        "direction:in",
        "manual_checkpoint:true",
        "topic:testTopic",
        "type:kafka"
      ]
      edgeTags.size() == 4
    }
  }
}
