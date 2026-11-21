package datadog.trace.agent.test.datastreams;

import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.core.datastreams.StatsBucket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class RecordingDatastreamsPayloadWriterTest {
  @Test
  void waitingDoesNotLockOtherWriters() throws Exception {
    RecordingDatastreamsPayloadWriter writer = new RecordingDatastreamsPayloadWriter();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      synchronized (RecordingDatastreamsPayloadWriter.class) {
        Future<?> waiting = executor.submit(() -> writer.waitForGroups(0, 0));
        waiting.get(1, SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void observesPayloadWrittenWhileWaiting() throws Exception {
    RecordingDatastreamsPayloadWriter writer = new RecordingDatastreamsPayloadWriter();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> waiting = executor.submit(() -> writer.waitForGroups(1, 3000));
      StatsBucket bucket = new StatsBucket(0, 1);
      bucket.addPoint(new StatsPoint(DataStreamsTags.EMPTY, 1, 0, 0, 0, 0, 0, 0, null));
      writer.writePayload(singletonList(bucket), null);
      waiting.get(5, SECONDS);
      assertEquals(1, writer.getGroups().size());
    } finally {
      executor.shutdownNow();
    }
  }
}
