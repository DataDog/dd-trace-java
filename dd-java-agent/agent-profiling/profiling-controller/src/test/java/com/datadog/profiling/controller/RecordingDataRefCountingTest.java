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

/** Tests for RecordingData reference counting with multiple handlers. */
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
    public Path getFile() {
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

    // Single handler: retain once, release once
    data.retain();
    assertEquals(0, data.getReleaseCount(), "Should not be released yet");

    data.release();

    assertTrue(data.awaitRelease(1, TimeUnit.SECONDS), "Release should be called");
    assertEquals(1, data.getReleaseCount(), "doRelease() should be called exactly once");
  }

  @Test
  public void testTwoHandlers() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();

    // Two handlers (e.g., JFR + OTLP): retain twice
    data.retain(); // Handler 1
    data.retain(); // Handler 2
    assertEquals(0, data.getReleaseCount(), "Should not be released yet");

    // First handler releases
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

    // Three handlers (e.g., dumper + JFR + OTLP): retain three times
    data.retain(); // Handler 1
    data.retain(); // Handler 2
    data.retain(); // Handler 3
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
  public void testReleaseBeforeRetain() {
    TestRecordingData data = new TestRecordingData();

    // Cannot release before any retain
    assertThrows(
        IllegalStateException.class,
        data::release,
        "Should throw when releasing with refcount=0");
  }

  @Test
  public void testRetainAfterFullRelease() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();

    data.retain();
    data.release();
    assertTrue(data.awaitRelease(1, TimeUnit.SECONDS), "Release should be called");

    // Cannot retain after full release
    assertThrows(
        IllegalStateException.class,
        data::retain,
        "Should throw when retaining after release");
  }

  @Test
  public void testMultipleReleaseIdempotent() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();

    data.retain();
    data.release();
    assertTrue(data.awaitRelease(1, TimeUnit.SECONDS), "Release should be called");

    // Additional release calls should be no-op
    data.release();
    data.release();

    assertEquals(1, data.getReleaseCount(), "doRelease() should still be called exactly once");
  }

  @Test
  public void testConcurrentHandlers() throws InterruptedException {
    TestRecordingData data = new TestRecordingData();
    int numHandlers = 10;

    // Retain for all handlers
    for (int i = 0; i < numHandlers; i++) {
      data.retain();
    }

    // Simulate concurrent release from multiple threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numHandlers);

    for (int i = 0; i < numHandlers; i++) {
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
