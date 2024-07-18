package datadog.trace.agent.test.datastreams

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import datadog.trace.api.experimental.DataStreamsCheckpointer
import datadog.trace.api.experimental.DataStreamsContextCarrier
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.core.datastreams.StatsGroup

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class ManualAPITest extends AgentTestRunner {
  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  void 'test extract protobuf schema on serialize & deserialize'() {
    when:
    runUnderTrace("parent_serialize") {
      DataStreamsCheckpointer.get().setProduceCheckpoint("kafka", "testTopic", DataStreamsContextCarrier.NoOp.INSTANCE)
      AgentSpan span = activeSpan()
      span.setTag(DDTags.MANUAL_KEEP, true)
    }
    then:
    TEST_DATA_STREAMS_WRITER.waitForPayloads(1)
    then:
    StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
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
}
