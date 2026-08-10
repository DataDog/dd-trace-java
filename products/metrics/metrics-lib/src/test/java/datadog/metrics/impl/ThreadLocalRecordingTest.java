package datadog.metrics.impl;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.metrics.api.Recording;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class ThreadLocalRecordingTest {

  @Test
  void delegatesPerThread() throws Exception {
    Map<Thread, List<String>> callsByThread = new ConcurrentHashMap<>();

    ThreadLocal<Recording> sink =
        ThreadLocal.withInitial(
            () -> {
              List<String> calls = new ArrayList<>();
              callsByThread.put(Thread.currentThread(), calls);
              return recordCalls(calls);
            });

    Recording recording = new ThreadLocalRecording(sink);

    int threadCount = 4;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch done = new CountDownLatch(threadCount);
    Thread[] threads = new Thread[threadCount];

    for (int i = 0; i < threadCount; i++) {
      threads[i] =
          new Thread(
              () -> {
                ready.countDown();
                try {
                  ready.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                recording.start();
                recording.reset();
                recording.stop();
                recording.flush();
                done.countDown();
              });
      threads[i].start();
    }
    done.await();

    assertEquals(threadCount, callsByThread.size(), "each thread should have its own Recording");
    for (List<String> calls : callsByThread.values()) {
      assertEquals(asList("start", "reset", "stop", "flush"), calls);
    }
  }

  private static Recording recordCalls(List<String> calls) {
    return new Recording() {
      @Override
      public Recording start() {
        calls.add("start");
        return this;
      }

      @Override
      public void reset() {
        calls.add("reset");
      }

      @Override
      public void stop() {
        calls.add("stop");
      }

      @Override
      public void flush() {
        calls.add("flush");
      }
    };
  }
}
