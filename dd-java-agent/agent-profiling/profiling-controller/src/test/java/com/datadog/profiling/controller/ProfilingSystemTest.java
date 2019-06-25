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

import static com.datadog.profiling.controller.RecordingType.CONTINUOUS;
import static com.datadog.profiling.controller.RecordingType.PERIODIC;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
// Proper unused stub detection doesn't work in junit5 yet,
// see https://github.com/mockito/mockito/issues/1540
@MockitoSettings(strictness = Strictness.LENIENT)
public class ProfilingSystemTest {

  // Time in MS when all things should have been done by
  // Should be noticeably bigger than one recording iteration
  private static final long REASONABLE_TIMEOUT = 5000;

  private final ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1);
  // private final AtomicInteger counter = new AtomicInteger();

  @Mock private Controller controller;
  @Mock private OngoingRecording continuousRecording;
  @Mock private OngoingRecording periodicRecording;
  @Mock private RecordingData recordingData;
  @Mock private RecordingDataListener listener;

  @BeforeEach
  public void setup() {
    when(controller.createContinuousRecording(ProfilingSystem.RECORDING_NAME))
        .thenReturn(continuousRecording);
    when(controller.createPeriodicRecording(ProfilingSystem.RECORDING_NAME))
        .thenReturn(periodicRecording);
  }

  @AfterEach
  public void tearDown() {
    pool.shutdown();
  }

  @Test
  public void testShutdown() throws ConfigurationException {
    final ProfilingSystem system =
        new ProfilingSystem(
            controller, listener, Duration.ofMillis(10), Duration.ofMillis(300), 0, pool);
    startProfilingSystem(system);
    verify(controller).createContinuousRecording(any());
    system.shutdown();

    verify(continuousRecording).close();
    assertTrue(pool.isTerminated());
  }

  @Test
  public void testShutdownWithRunningProfilingRecording() throws ConfigurationException {
    final ProfilingSystem system =
        new ProfilingSystem(
            controller, listener, Duration.ofMillis(10), Duration.ofMillis(300), 1, pool);
    startProfilingSystem(system);
    verify(controller).createPeriodicRecording(any());
    system.shutdown();

    verify(periodicRecording).close();
    verify(continuousRecording).close();
    assertTrue(pool.isTerminated());
  }

  @Test
  public void testShutdownInterruption() throws ConfigurationException {
    final Thread mainThread = Thread.currentThread();
    doAnswer(
            (InvocationOnMock invocation) -> {
              while (!pool.isShutdown()) {
                try {
                  Thread.sleep(100);
                } catch (InterruptedException e) {
                  // Ignore InterruptedException to make sure this threads lives through executor
                  // shutdown
                }
              }
              // Interrupting main thread to make sure this is handled properly
              mainThread.interrupt();
              return null;
            })
        .when(controller)
        .createPeriodicRecording(any());
    final ProfilingSystem system =
        new ProfilingSystem(
            controller, listener, Duration.ofMillis(10), Duration.ofMillis(100), 2, pool);
    startProfilingSystem(system);
    // Make sure we actually started the recording before terminating
    verify(controller, timeout(300)).createPeriodicRecording(any());
    system.shutdown();
    assertTrue(true, "Shutdown exited cleanly after interruption");
  }

  @Test
  public void testCanShutDownWithoutStarting() throws ConfigurationException {
    final ProfilingSystem system =
        new ProfilingSystem(
            controller, listener, Duration.ofMillis(10), Duration.ofMillis(300), 1, pool);
    system.shutdown();
    assertTrue(pool.isTerminated());
  }

  @Test
  public void testDoesntSendDataIfNotStarted() throws InterruptedException, ConfigurationException {
    final ProfilingSystem system =
        new ProfilingSystem(controller, listener, Duration.ofMillis(10), Duration.ofMillis(1), 1);
    Thread.sleep(50);
    system.shutdown();
    verify(controller, never()).createPeriodicRecording(any());
    verify(controller, never()).createContinuousRecording(any());
    verify(listener, never()).onNewData(any(), any());
  }

  @Test
  public void testDoesntSendPeriodicRecordingIfPeriodicRecordingIsDisabled()
      throws InterruptedException, ConfigurationException {
    when(continuousRecording.snapshot(any(), any())).thenReturn(recordingData);
    final ProfilingSystem system =
        new ProfilingSystem(controller, listener, Duration.ofMillis(10), Duration.ofMillis(10), 0);
    startProfilingSystem(system);
    Thread.sleep(200);
    system.shutdown();
    verify(controller, never()).createPeriodicRecording(any());
    verify(listener, never()).onNewData(eq(PERIODIC), any());
    verify(listener, atLeastOnce()).onNewData(CONTINUOUS, recordingData);
  }

  @Test
  public void testProfilingSystemNegativeStartupDelay() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              controller, listener, Duration.ofMillis(-10), Duration.ofMillis(200), 1);
        });
  }

  @Test
  public void testProfilingSystemNegativeUploadPeriod() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              controller, listener, Duration.ofMillis(10), Duration.ofMillis(-200), 1);
        });
  }

  @Test
  public void testProfilingSystemNegativePeriodicRatioPeriod() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              controller, listener, Duration.ofMillis(10), Duration.ofMillis(200), -1);
        });
  }

  @Test
  public void testAlternatingContinuousAndPeriodicRecordings() throws ConfigurationException {
    final Duration uploadPeriod = Duration.ofMillis(300);
    final List<RecordingData> generatedRecordingData = new ArrayList<>();
    when(continuousRecording.snapshot(any(), any()))
        .thenAnswer(generateMockRecordingData(generatedRecordingData));

    final List<OngoingRecording> generatedPeriodicRecordings = new ArrayList<>();
    when(controller.createPeriodicRecording(ProfilingSystem.RECORDING_NAME))
        .thenAnswer(
            (InvocationOnMock invocation) -> {
              final OngoingRecording recording = mock(OngoingRecording.class);
              generatedPeriodicRecordings.add(recording);
              return recording;
            });

    final ProfilingSystem system =
        new ProfilingSystem(controller, listener, Duration.ofMillis(10), uploadPeriod, 2, pool);
    startProfilingSystem(system);

    final ArgumentCaptor<RecordingType> recordingTypeCaptor =
        ArgumentCaptor.forClass(RecordingType.class);
    final ArgumentCaptor<RecordingData> recordingDataCaptor =
        ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(4))
        .onNewData(recordingTypeCaptor.capture(), recordingDataCaptor.capture());
    assertEquals(
        ImmutableList.of(CONTINUOUS, PERIODIC, CONTINUOUS, PERIODIC),
        recordingTypeCaptor.getAllValues());
    assertEquals(generatedRecordingData, recordingDataCaptor.getAllValues());

    system.shutdown();

    verify(listener, after(REASONABLE_TIMEOUT).atMost(5))
        .onNewData(any(), recordingDataCaptor.capture());
    assertEquals(generatedRecordingData, recordingDataCaptor.getAllValues());

    // Make sure periodic recordings are being closed
    // TODO: should we somehow check that they are closed at the right time>
    for (final OngoingRecording recording : generatedPeriodicRecordings) {
      verify(recording).close();
    }

    for (int i = 0; i < recordingDataCaptor.getAllValues().size(); i++) {
      final RecordingData recording = recordingDataCaptor.getAllValues().get(i);
      assertTrue(
          // There may be slight variation in processing time so we give it 10ms of leeway
          recording.getStart().plus(uploadPeriod).isBefore(recording.getEnd().plusMillis(10)),
          "recording "
              + i
              + " has required duration: "
              + recording.getStart().plus(uploadPeriod)
              + " is before "
              + recording.getEnd());
    }

    final Instant firstRecordingStart = recordingDataCaptor.getAllValues().get(1).getStart();
    final Instant secondRecordingStart = recordingDataCaptor.getAllValues().get(2).getStart();
    assertTrue(
        firstRecordingStart
            .plus(uploadPeriod)
            // It looks like jobs get scheduled slightly earlier so we give a 10ms of leeway
            .isBefore(secondRecordingStart.plusMillis(10)),
        "recordings have required period: "
            + firstRecordingStart.plus(uploadPeriod)
            + " is before "
            + secondRecordingStart);
  }

  @Test
  public void testAlwaysOnPeriodicRecording() throws ConfigurationException {
    final Duration uploadPeriod = Duration.ofMillis(300);
    final List<RecordingData> generatedRecordingData = new ArrayList<>();
    when(continuousRecording.snapshot(any(), any()))
        .thenAnswer(generateMockRecordingData(generatedRecordingData));

    final ProfilingSystem system =
        new ProfilingSystem(controller, listener, Duration.ofMillis(10), uploadPeriod, 1, pool);
    startProfilingSystem(system);

    final ArgumentCaptor<RecordingData> captor = ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(3))
        .onNewData(eq(PERIODIC), captor.capture());
    assertEquals(generatedRecordingData, captor.getAllValues());
    verify(periodicRecording, never()).close();

    system.shutdown();

    verify(listener, after(REASONABLE_TIMEOUT).atMost(4)).onNewData(eq(PERIODIC), captor.capture());
    assertEquals(generatedRecordingData, captor.getAllValues());
    verify(periodicRecording).close();
  }

  /** Ensure that we continue recording after one recording fails to get created */
  @Test
  public void testRecordingSnapshotError() throws ConfigurationException {
    final Duration uploadPeriod = Duration.ofMillis(300);
    final List<RecordingData> generatedRecordingData = new ArrayList<>();
    when(continuousRecording.snapshot(any(), any()))
        .thenThrow(new RuntimeException("Test"))
        .thenAnswer(generateMockRecordingData(generatedRecordingData));

    final ProfilingSystem system =
        new ProfilingSystem(controller, listener, Duration.ofMillis(10), uploadPeriod, 0, pool);
    startProfilingSystem(system);

    final ArgumentCaptor<RecordingData> captor = ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(2))
        .onNewData(eq(CONTINUOUS), captor.capture());
    assertEquals(generatedRecordingData, captor.getAllValues());

    system.shutdown();
  }

  @Test
  public void testRecordingSnapshotNoData() throws ConfigurationException {
    final Duration uploadPeriod = Duration.ofMillis(300);
    final List<RecordingData> generatedRecordingData = new ArrayList<>();
    when(continuousRecording.snapshot(any(), any()))
        .thenReturn(null)
        .thenAnswer(generateMockRecordingData(generatedRecordingData));

    final ProfilingSystem system =
        new ProfilingSystem(controller, listener, Duration.ofMillis(10), uploadPeriod, 0, pool);
    startProfilingSystem(system);

    final ArgumentCaptor<RecordingData> captor = ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(2))
        .onNewData(eq(CONTINUOUS), captor.capture());
    assertEquals(generatedRecordingData, captor.getAllValues());

    system.shutdown();
  }

  private Answer<Object> generateMockRecordingData(
      final List<RecordingData> generatedRecordingData) {
    return (InvocationOnMock invocation) -> {
      final RecordingData recordingData = mock(RecordingData.class);
      when(recordingData.getStart()).thenReturn(invocation.getArgument(0, Instant.class));
      when(recordingData.getEnd()).thenReturn(invocation.getArgument(1, Instant.class));
      generatedRecordingData.add(recordingData);
      return recordingData;
    };
  }

  private void startProfilingSystem(final ProfilingSystem system) {
    system.start();
    await().until(system::isStarted);
  }
}
