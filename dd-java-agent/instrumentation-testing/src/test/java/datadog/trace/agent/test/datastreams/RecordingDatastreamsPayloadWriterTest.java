package datadog.trace.agent.test.datastreams;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.core.datastreams.StatsBucket;
import datadog.trace.core.datastreams.StatsGroup;
import org.junit.jupiter.api.Test;

class RecordingDatastreamsPayloadWriterTest {

  @Test
  void waitForGroupWaitsForAMatchingGroup() throws InterruptedException {
    // setup
    RecordingDatastreamsPayloadWriter writer = new RecordingDatastreamsPayloadWriter();
    Thread worker = startWorker(writer, 50L);

    // when
    StatsGroup group =
        writer.waitForGroup(candidate -> candidate.getParentHash() == 0, SECONDS.toMillis(1));

    // then
    assertEquals(0L, group.getParentHash());

    // cleanup
    worker.join(SECONDS.toMillis(2));
  }

  @Test
  void waitForGroupFailsByTimeout() throws InterruptedException {
    // setup
    RecordingDatastreamsPayloadWriter writer = new RecordingDatastreamsPayloadWriter();
    Thread worker = startWorker(writer, SECONDS.toMillis(3));

    // when
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                writer.waitForGroup(
                    candidate -> candidate.getParentHash() == 0, SECONDS.toMillis(1)));

    // then
    String message = error.getMessage();
    assertTrue(
        message.contains("Expected a matching stats group within 1000ms"),
        "Unexpected failure message: " + message);

    // cleanup
    worker.join(SECONDS.toMillis(5));
  }

  private static Thread startWorker(RecordingDatastreamsPayloadWriter writer, long delayMillis) {
    Thread worker =
        new Thread(
            () -> {
              writer.writePayload(
                  singletonList(bucket(1L, 123L, DataStreamsTags.create("test1", INBOUND))), null);
              try {
                Thread.sleep(delayMillis);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              writer.writePayload(
                  singletonList(bucket(2L, 0L, DataStreamsTags.create("test2", INBOUND))), null);
            });
    worker.setDaemon(true);
    worker.start();
    return worker;
  }

  private static StatsBucket bucket(long hash, long parentHash, DataStreamsTags tags) {
    StatsBucket bucket = new StatsBucket(0, SECONDS.toNanos(1L));
    bucket.addPoint(new StatsPoint(tags, hash, parentHash, hash, 0, 1, 1, 0, null));
    return bucket;
  }
}
