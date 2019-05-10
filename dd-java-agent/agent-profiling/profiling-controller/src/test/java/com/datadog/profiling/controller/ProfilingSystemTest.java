/*
 * Copyright 2019 Datadog
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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Unit tests for testing the {@link com.datadog.profiling.controller.ProfilingSystem}. */
public class ProfilingSystemTest {
  @Test
  public void testCanShutDownWithoutStarting()
      throws UnsupportedEnvironmentException, IOException, InterruptedException,
          BadConfigurationException {
    final RecordingDataListener listener =
        new RecordingDataListener() {
          @Override
          public void onNewData(final RecordingData data) {}
        };
    final ProfilingSystem system =
        new ProfilingSystem(
            listener, Duration.ZERO, Duration.ofMillis(200), Duration.ofMillis(100));
    system.shutdown();
  }

  @Test
  public void testDoesntSendDataIfNotStarted()
      throws UnsupportedEnvironmentException, IOException, InterruptedException,
          BadConfigurationException {
    final CountDownLatch latch = new CountDownLatch(1);
    final RecordingDataListener listener =
        new RecordingDataListener() {
          @Override
          public void onNewData(final RecordingData data) {
            latch.countDown();
          }
        };
    final ProfilingSystem system =
        new ProfilingSystem(listener, Duration.ZERO, Duration.ofMillis(1), Duration.ofMillis(1));
    latch.await(10, TimeUnit.MILLISECONDS);
    assertEquals(
        "Got recording data even though the system was never started!", 1, latch.getCount());
  }

  /**
   * Ensuring it is not possible to configure the profiling system to have greater recording lengths
   * than the dump periodicity.
   *
   * @throws InterruptedException
   */
  @Test
  public void testProfilingSystemCreationBadConfig()
      throws UnsupportedEnvironmentException, IOException, InterruptedException {
    final RecordingDataListener listener =
        new RecordingDataListener() {
          @Override
          public void onNewData(final RecordingData data) {
            // Don't care...
          }
        };
    try {
      final ProfilingSystem system =
          new ProfilingSystem(
              listener, Duration.ZERO, Duration.ofMillis(200), Duration.ofMillis(400));
      system.shutdown();
    } catch (final Exception e) {
      return;
    }

    Assert.fail("An exception should have been thrown at this point!");
  }

  /**
   * Ensuring that it can be started, and recording data for a few profiling recordings captured.
   *
   * @throws InterruptedException
   * @throws com.datadog.profiling.controller.BadConfigurationException
   */
  @Test
  public void testProfilingSystem()
      throws UnsupportedEnvironmentException, IOException, InterruptedException,
          BadConfigurationException {
    final CountDownLatch latch = new CountDownLatch(2);
    final List<RecordingData> results = new ArrayList<>();

    final RecordingDataListener listener =
        new RecordingDataListener() {
          @Override
          public void onNewData(final RecordingData data) {
            results.add(data);
            latch.countDown();
          }
        };
    final ProfilingSystem system =
        new ProfilingSystem(listener, Duration.ZERO, Duration.ofMillis(200), Duration.ofMillis(25));
    system.start();
    latch.await(30, TimeUnit.SECONDS);
    assertTrue("Should have received more data!", results.size() >= 2);
    for (final RecordingData data : results) {
      assertTrue("RecordingData should be available before sent out!", data.isAvailable());
    }
    system.shutdown();
    for (final RecordingData data : results) {
      data.release();
    }
  }

  /**
   * Ensuring that it can be started, and recording data for the continuous recording captured.
   *
   * @throws InterruptedException
   * @throws com.datadog.profiling.controller.BadConfigurationException
   */
  @Test
  public void testContinuous()
      throws UnsupportedEnvironmentException, IOException, InterruptedException,
          BadConfigurationException {
    final CountDownLatch latch = new CountDownLatch(2);
    final List<RecordingData> results = new ArrayList<>();

    final RecordingDataListener listener =
        new RecordingDataListener() {
          @Override
          public void onNewData(final RecordingData data) {
            results.add(data);
            latch.countDown();
          }
        };
    final ProfilingSystem system =
        new ProfilingSystem(
            listener, Duration.ofDays(1), Duration.ofDays(1), Duration.ofSeconds(2));
    system.start();
    final Runnable continuousTrigger =
        new Runnable() {
          @Override
          public void run() {
            for (int i = 0; i < 3; i++) {
              try {
                system.triggerSnapshot();
                try {
                  Thread.sleep(200);
                } catch (final InterruptedException e) {
                }
              } catch (final IOException e) {
                e.printStackTrace();
              }
            }
          }
        };
    new Thread(continuousTrigger, "Continuous trigger").start();
    latch.await(30, TimeUnit.SECONDS);
    assertTrue("Should have received more data!", results.size() >= 2);
    for (final RecordingData data : results) {
      assertFalse(
          "Should not be getting profiling recordings!", data.getName().startsWith("dd-profiling"));
      assertTrue("RecordingData should be available before sent out!", data.isAvailable());
    }
    system.shutdown();
    for (final RecordingData data : results) {
      data.release();
    }
  }
}
