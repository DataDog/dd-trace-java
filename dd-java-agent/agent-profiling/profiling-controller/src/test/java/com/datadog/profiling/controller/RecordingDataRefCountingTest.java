/*
 * Copyright 2025 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests for RecordingData reference counting with multiple handlers. The reference count starts at
 * 1 (the base reference). The primary listener releases the base reference; each additional
 * listener calls retain() then release().
 */
public class RecordingDataRefCountingTest {

  /** Test RecordingData implementation that tracks release calls. */
  private static class TestRecordingData extends RecordingData {
    private final AtomicInteger releaseCount = new AtomicInteger(0);
    private final CountDownLatch releaseLatch = new CountDownLatch(1);

    public TestRecordingData() {
      super(Instant.now(), Instant.now(), ProfilingSnapshot.Kind.PERIODIC);
    }

    @Nonnull
    @Override
    public RecordingInputStream getStream() throws IOException {
      return new RecordingInputStream(new ByteArrayInputStream(new byte[0]));
    }

    @Override
    protected void doRelease() {
      releaseCount.incrementAndGet();
      releaseLatch.countDown();
    }

    @Nullable
    @Override
    public Path getPath() {
      return null;
    }

    @Override
    public String getName() {
      return "test-recording";
    }

    public int getReleaseCount() {
      return releaseCount.get();
    }

    public boolean awaitRelease(long timeout, TimeUnit unit) throws InterruptedException {
      return releaseLatch.await(timeout, unit);
    }
  }

  @Test
  public void testSingleHandler() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();

    // Single handler: just release the base reference (no retain needed)
    data.release();

    assertTrue(data.awaitRelease(1, TimeUnit.SECONDS), "Release should be called");
    assertEquals(1, data.getReleaseCount(), "doRelease() should be called exactly once");
  }

  @Test
  public void testTwoHandlers() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();

    // Two handlers: retain once for the additional handler, release twice
    data.retain(); // Additional handler (e.g., OTLP)
    assertEquals(0, data.getReleaseCount(), "Should not be released yet");

    // First handler releases (base reference)
    data.release();
    assertEquals(0, data.getReleaseCount(), "Should not be released after first release");

    // Second handler releases
    data.release();

    assertTrue(data.awaitRelease(1, TimeUnit.SECONDS), "Release should be called");
    assertEquals(1, data.getReleaseCount(), "doRelease() should be called exactly once");
  }

  @Test
  public void testThreeHandlers() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();

    // Three handlers: retain twice, release three times
    data.retain(); // Additional handler 1
    data.retain(); // Additional handler 2
    assertEquals(0, data.getReleaseCount(), "Should not be released yet");

    // First two handlers release
    data.release();
    data.release();
    assertEquals(0, data.getReleaseCount(), "Should not be released after two releases");

    // Third handler releases
    data.release();

    assertTrue(data.awaitRelease(1, TimeUnit.SECONDS), "Release should be called");
    assertEquals(1, data.getReleaseCount(), "doRelease() should be called exactly once");
  }

  @Test
  public void testOverReleaseDetected() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();

    // Release the base reference
    data.release();
    assertTrue(data.awaitRelease(1, TimeUnit.SECONDS), "Release should be called");

    // Additional release after full release is a silent no-op (released flag guards it)
    data.release();
    assertEquals(1, data.getReleaseCount(), "doRelease() should still be called exactly once");
  }

  @Test
  public void testRetainAfterFullRelease() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();

    data.release();
    assertTrue(data.awaitRelease(1, TimeUnit.SECONDS), "Release should be called");

    // Cannot retain after full release
    assertThrows(
        IllegalStateException.class, data::retain, "Should throw when retaining after release");
  }

  @Test
  public void testMultipleReleaseIdempotent() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();

    data.release();
    assertTrue(data.awaitRelease(1, TimeUnit.SECONDS), "Release should be called");

    // Additional release calls should be silent no-ops (released flag guards them)
    data.release();
    data.release();

    assertEquals(1, data.getReleaseCount(), "doRelease() should still be called exactly once");
  }

  @Test
  public void testConcurrentHandlers() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();
    int numAdditionalHandlers = 9; // total 10 with the base reference

    // Retain for all additional handlers
    for (int i = 0; i < numAdditionalHandlers; i++) {
      data.retain();
    }

    int totalHandlers = numAdditionalHandlers + 1;

    // Simulate concurrent release from multiple threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(totalHandlers);

    for (int i = 0; i < totalHandlers; i++) {
      new Thread(
              () -> {
                try {
                  startLatch.await();
                  data.release();
                  doneLatch.countDown();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              })
          .start();
    }

    // Start all threads
    startLatch.countDown();

    // Wait for all threads to complete
    assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete");
    assertTrue(data.awaitRelease(1, TimeUnit.SECONDS), "Release should be called");
    assertEquals(1, data.getReleaseCount(), "doRelease() should be called exactly once");
  }

  @Test
  public void testRetainChaining() {
    TestRecordingData data = new TestRecordingData();

    // retain() should return this for chaining
    RecordingData result = data.retain();
    assertEquals(data, result, "retain() should return the same instance");
  }
}
