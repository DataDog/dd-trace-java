package datadog.trace.agent.test.datastreams

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND
import static java.util.concurrent.TimeUnit.SECONDS

import datadog.trace.api.datastreams.DataStreamsTags
import datadog.trace.api.datastreams.StatsPoint
import datadog.trace.core.datastreams.StatsBucket
import datadog.trace.core.datastreams.StatsGroup
import spock.lang.Specification

class RecordingDatastreamsPayloadWriterTest extends Specification {
  private static Thread startWorker(RecordingDatastreamsPayloadWriter writer, long delay) {
    return Thread.startDaemon {
      writer.writePayload([bucket(1L, 123L, DataStreamsTags.create("test1", INBOUND))], null)
      Thread.sleep(delay)
      writer.writePayload([bucket(2L, 0L, DataStreamsTags.create("test2", INBOUND))], null)
    }
  }


  def "waitForGroup waits for a matching group"() {
    setup:
    def writer = new RecordingDatastreamsPayloadWriter()
    def worker = startWorker(writer, 50L)

    when:
    StatsGroup group = writer.waitForGroup({ it.parentHash == 0 }, SECONDS.toMillis(1))

    then:
    group.parentHash == 0

    cleanup:
    worker.join(SECONDS.toMillis(2))
  }

  def "waitForGroup fails by timeout"() {
    setup:
    def writer = new RecordingDatastreamsPayloadWriter()
    def worker = startWorker(writer, SECONDS.toMillis(3))

    when:
    writer.waitForGroup({ it.parentHash == 0 }, SECONDS.toMillis(1))

    then:
    AssertionError error = thrown()
    error.message.contains("Expected a matching stats group within 1000ms")

    cleanup:
    worker.join(SECONDS.toMillis(5))
  }

  private static StatsBucket bucket(long hash, long parentHash, DataStreamsTags tags) {
    StatsBucket bucket = new StatsBucket(0, SECONDS.toNanos(1L))
    bucket.addPoint(new StatsPoint(tags, hash, parentHash, hash, 0, 1, 1, 0, null))
    return bucket
  }
}
