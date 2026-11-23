import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.instrumentation.kafka_clients38.DDOffsetCommitCallback
import datadog.trace.instrumentation.kafka_clients38.KafkaConsumerInfo
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.OffsetCommitCallback
import org.apache.kafka.common.TopicPartition
import spock.util.concurrent.PollingConditions

/**
 * Unit test to verify that DSM backlog tracking works even when consumer group is null.
 *
 * This test reproduces a bug where calling Optional.get() on an empty Optional
 * (when consumer group is null) throws NoSuchElementException, causing the entire
 * backlog tracking to fail silently.
 *
 * Before the fix:
 * - KafkaConsumerInfo.getConsumerGroup() returns Optional.empty() when consumer group is null
 * - DDOffsetCommitCallback calls .get() on this empty Optional
 * - NoSuchElementException is thrown
 * - No backlog metrics are emitted
 *
 * After the fix (using .orElse(null) instead of .get()):
 * - Backlog metrics are emitted even with null consumer group
 */
class DDOffsetCommitCallbackNullGroupForkedTest extends VersionedNamingTestBase {

  @Override
  int version() {
    return 0
  }

  @Override
  String operation() {
    return null
  }

  @Override
  String service() {
    return "test-service"
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  def "test DDOffsetCommitCallback handles null consumer group without exception"() {
    setup:
    // Create KafkaConsumerInfo with NULL consumer group - this is the key to reproducing the bug
    def kafkaConsumerInfo = new KafkaConsumerInfo(null, null, "localhost:9092")

    // Verify the consumer group is indeed empty
    assert !kafkaConsumerInfo.getConsumerGroup().isPresent() : "Consumer group should be empty for this test"

    def callbacks = []
    def innerCallback = new OffsetCommitCallback() {
        @Override
        void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
          callbacks.add([offsets, exception, Thread.currentThread()])
        }
      }

    def ddCallback = new DDOffsetCommitCallback(innerCallback, kafkaConsumerInfo, null)

    def offsets = [
      (new TopicPartition("test-topic", 0)): new OffsetAndMetadata(100L)
    ]

    when:
    // This should NOT throw an exception
    // Before the fix: NoSuchElementException would be thrown here
    // After the fix: completes successfully with null consumer group
    ddCallback.onComplete(offsets, null)

    then:
    // Inner callback should have been invoked
    callbacks == [[offsets, null, Thread.currentThread()]]

    // No exception was thrown (implicit - test would fail otherwise)
    noExceptionThrown()
    awaitBacklog(null)
  }

  def "test DDOffsetCommitCallback handles null KafkaConsumerInfo"() {
    setup:
    def callbacks = []
    def innerCallback = new OffsetCommitCallback() {
        @Override
        void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
          callbacks.add([offsets, exception, Thread.currentThread()])
        }
      }

    // KafkaConsumerInfo is completely null
    def ddCallback = new DDOffsetCommitCallback(innerCallback, null, null)

    def offsets = [
      (new TopicPartition("test-topic", 0)): new OffsetAndMetadata(100L)
    ]

    when:
    ddCallback.onComplete(offsets, null)

    then:
    callbacks == [[offsets, null, Thread.currentThread()]]
    noExceptionThrown()
    awaitBacklog(null)
  }

  def "test DDOffsetCommitCallback with valid consumer group still works"() {
    setup:
    def kafkaConsumerInfo = new KafkaConsumerInfo("test-group", null, "localhost:9092")

    assert kafkaConsumerInfo.getConsumerGroup().isPresent()
    assert kafkaConsumerInfo.getConsumerGroup().get() == "test-group"

    def callbacks = []
    def innerCallback = new OffsetCommitCallback() {
        @Override
        void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
          callbacks.add([offsets, exception, Thread.currentThread()])
        }
      }

    def ddCallback = new DDOffsetCommitCallback(innerCallback, kafkaConsumerInfo, null)

    def offsets = [
      (new TopicPartition("test-topic", 0)): new OffsetAndMetadata(100L)
    ]

    when:
    ddCallback.onComplete(offsets, null)

    then:
    callbacks == [[offsets, null, Thread.currentThread()]]
    noExceptionThrown()
    awaitBacklog("test-group")
  }

  private void awaitBacklog(String group) {
    new PollingConditions(timeout: 15).eventually {
      assert TEST_DATA_STREAMS_WRITER.payloads.any { bucket ->
        bucket.backlogs.any { entry ->
          entry.value == 100L && entry.key.kafkaClusterId == null &&
            entry.key.consumerGroup == (group == null ? null : "consumer_group:$group".toString()) &&
            entry.key.hasAllTags("type:kafka_commit", "topic:test-topic", "partition:0")
        }
      }
    }
  }
}
