package com.datadog.mlt.sampler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datadog.mlt.io.FrameElement;
import com.datadog.mlt.io.FrameSequence;
import com.datadog.mlt.io.IMLTChunk;
import com.datadog.mlt.io.MLTChunk;
import com.datadog.mlt.io.MLTReader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScopeManagerTest {
  private ThreadScopeMapper global;

  @BeforeEach
  public void setup() throws Exception {
    global = new ThreadScopeMapper();
  }

  @Test
  void sample() throws Exception {
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    Thread workerThread =
        new Thread(
            () -> {
              try {
                while (true) {
                  mainEntry();
                }
              } catch (InterruptedException ignored) {

              }
            });
    workerThread.setDaemon(true);
    workerThread.start();

    ScopeManager scopeManager = global.forThread(workerThread);

    ScopeStackCollector sampler = scopeManager.startScope("main");
    for (int i = 0; i < 100; i++) {
      ThreadInfo ti = threadMXBean.getThreadInfo(workerThread.getId(), 2048);
      sampler.collect(ti.getStackTrace());
      Thread.sleep(ThreadLocalRandom.current().nextInt(20) + 2);
    }
    IMLTChunk collectedChunk = sampler.end();

    byte[] serializedChunk = collectedChunk.serialize();
    List<MLTChunk> chunks = new MLTReader().readMLTChunks(serializedChunk);
    assertFalse(chunks.isEmpty());
    assertEquals(1, chunks.size());

    MLTChunk chunk = chunks.get(0);
    assertEquals(collectedChunk.getVersion(), chunk.getVersion());
    assertEquals(collectedChunk.getStartTime(), chunk.getStartTime());
    assertEquals(collectedChunk.getThreadId(), chunk.getThreadId());
    assertEquals(collectedChunk.getThreadName(), chunk.getThreadName());

    int[] origStackPtrs = collectedChunk.frameSequenceCpIndexes().toArray();
    int[] restoredFramesPtrs = chunk.frameSequenceCpIndexes().toArray();
    assertArrayEquals(origStackPtrs, restoredFramesPtrs);

    /*
     * Can't compare the framestack instances directly since the distribution between the captured frames and
     * the referenced subtree is a subject to change while serializing the collected data.
     */
    FrameElement[] origFrames =
        collectedChunk.frameSequences().flatMap(FrameSequence::frames).toArray(FrameElement[]::new);
    FrameElement[] restoredFrames =
        chunk.frameSequences().flatMap(FrameSequence::frames).toArray(FrameElement[]::new);
    assertArrayEquals(origFrames, restoredFrames);
  }

  private void mainEntry() throws InterruptedException {
    int dispatch = ThreadLocalRandom.current().nextInt(4);
    switch (dispatch) {
      case 1:
        entry1(0);
      case 2:
        entry2(0);
      case 3:
        entry3(0);
    }
  }

  private void entry7(int level) throws InterruptedException {
    if (level > 2048) {
      return;
    }
    Thread.sleep(7);
    int dispatch = ThreadLocalRandom.current().nextInt(8);
    switch (dispatch) {
      case 1:
        entry1(level + 1);
        break;
      case 2:
        entry2(level + 1);
        break;
      case 3:
        entry3(level + 1);
        break;
      case 4:
        entry4(level + 1);
        break;
      case 5:
        entry5(level + 1);
        break;
      case 6:
        entry6(level + 1);
        break;
      case 7:
        entry7(level + 1);
        break;
    }
  }

  private void entry6(int level) throws InterruptedException {
    if (level > 2048) {
      return;
    }
    Thread.sleep(6);
    int dispatch = ThreadLocalRandom.current().nextInt(8);
    switch (dispatch) {
      case 1:
        entry1(level + 1);
        break;
      case 2:
        entry2(level + 1);
        break;
      case 3:
        entry3(level + 1);
        break;
      case 4:
        entry4(level + 1);
        break;
      case 5:
        entry5(level + 1);
        break;
      case 6:
        entry6(level + 1);
        break;
      case 7:
        entry7(level + 1);
        break;
    }
  }

  private void entry5(int level) throws InterruptedException {
    if (level > 2048) {
      return;
    }
    Thread.sleep(5);
    int dispatch = ThreadLocalRandom.current().nextInt(8);
    switch (dispatch) {
      case 1:
        entry1(level + 1);
        break;
      case 2:
        entry2(level + 1);
        break;
      case 3:
        entry3(level + 1);
        break;
      case 4:
        entry4(level + 1);
        break;
      case 5:
        entry5(level + 1);
        break;
      case 6:
        entry6(level + 1);
        break;
      case 7:
        entry7(level + 1);
        break;
    }
  }

  private void entry4(int level) throws InterruptedException {
    if (level > 2048) {
      return;
    }
    Thread.sleep(4);
    int dispatch = ThreadLocalRandom.current().nextInt(8);
    switch (dispatch) {
      case 1:
        entry1(level + 1);
        break;
      case 2:
        entry2(level + 1);
        break;
      case 3:
        entry3(level + 1);
        break;
      case 4:
        entry4(level + 1);
        break;
      case 5:
        entry5(level + 1);
        break;
      case 6:
        entry6(level + 1);
        break;
      case 7:
        entry7(level + 1);
        break;
    }
  }

  private void entry3(int level) throws InterruptedException {
    if (level > 2048) {
      return;
    }
    Thread.sleep(3);
    int dispatch = ThreadLocalRandom.current().nextInt(8);
    switch (dispatch) {
      case 1:
        entry1(level + 1);
        break;
      case 2:
        entry2(level + 1);
        break;
      case 3:
        entry3(level + 1);
        break;
      case 4:
        entry4(level + 1);
        break;
      case 5:
        entry5(level + 1);
        break;
      case 6:
        entry6(level + 1);
        break;
      case 7:
        entry7(level + 1);
        break;
    }
  }

  private void entry2(int level) throws InterruptedException {
    if (level > 2048) {
      return;
    }
    Thread.sleep(2);
    int dispatch = ThreadLocalRandom.current().nextInt(8);
    switch (dispatch) {
      case 1:
        entry1(level + 1);
        break;
      case 2:
        entry2(level + 1);
        break;
      case 3:
        entry3(level + 1);
        break;
      case 4:
        entry4(level + 1);
        break;
      case 5:
        entry5(level + 1);
        break;
      case 6:
        entry6(level + 1);
        break;
      case 7:
        entry7(level + 1);
        break;
    }
  }

  private void entry1(int level) throws InterruptedException {
    if (level > 2048) {
      return;
    }
    Thread.sleep(1);
    int dispatch = ThreadLocalRandom.current().nextInt(8);
    switch (dispatch) {
      case 1:
        entry1(level + 1);
        break;
      case 2:
        entry2(level + 1);
        break;
      case 3:
        entry3(level + 1);
        break;
      case 4:
        entry4(level + 1);
        break;
      case 5:
        entry5(level + 1);
        break;
      case 6:
        entry6(level + 1);
        break;
      case 7:
        entry7(level + 1);
        break;
    }
  }
}
