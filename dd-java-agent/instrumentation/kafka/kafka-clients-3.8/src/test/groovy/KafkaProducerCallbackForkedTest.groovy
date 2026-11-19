import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.instrumentation.kafka_clients38.KafkaProducerCallback
import datadog.trace.instrumentation.kafka_common.MetadataState
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import spock.util.concurrent.PollingConditions

/**
 * A send that starts before the producer's first metadata update has no cluster ID; the callback
 * reads it from the metadata state when the send completes.
 */
class KafkaProducerCallbackForkedTest extends VersionedNamingTestBase {

  @Override
  int version() {
    0
  }

  @Override
  String operation() {
    null
  }

  @Override
  String service() {
    "kafka"
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    true
  }

  def "callback tags the cluster id recorded after the send started"() {
    setup:
    def metadataState = new MetadataState()
    def span = startSpan("kafka", "kafka.produce")
    // the send started before the first metadata update, so it has no cluster ID
    def callback = new KafkaProducerCallback(null, null, span, null, metadataState)
    assert span.getTag(InstrumentationTags.KAFKA_CLUSTER_ID) == null

    when:
    // the metadata update the send waited for records the cluster ID
    metadataState.clusterId = "test-cluster-id"
    callback.onCompletion(new RecordMetadata(new TopicPartition("test-topic", 0), 100L, 0, 0L, 0, 0), null)
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.flatten()*.getTag(InstrumentationTags.KAFKA_CLUSTER_ID) == ["test-cluster-id"]
    new PollingConditions(timeout: 15).eventually {
      assert TEST_DATA_STREAMS_WRITER.payloads.any { bucket ->
        bucket.backlogs.any { entry ->
          entry.value == 100L &&
            entry.key.hasAllTags("type:kafka_produce", "topic:test-topic", "partition:0",
            "kafka_cluster_id:test-cluster-id")
        }
      }
    }
  }
}
