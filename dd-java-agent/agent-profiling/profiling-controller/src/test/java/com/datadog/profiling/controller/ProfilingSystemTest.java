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

import static datadog.trace.api.profiling.RecordingType.CONTINUOUS;
import static datadog.trace.util.AgentThreadFactory.AgentThread.PROFILER_RECORDING_SCHEDULER;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.AgentTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.internal.verification.VerificationModeFactory;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
// Proper unused stub detection doesn't work in junit5 yet,
// see https://github.com/mockito/mockito/issues/1540
@MockitoSettings(strictness = Strictness.LENIENT)
public class ProfilingSystemTest {

  // Time in milliseconds when all things should have been done by
  // Should be noticeably bigger than one recording iteration
  private static final long REASONABLE_TIMEOUT = 5000;

  private final AgentTaskScheduler scheduler = new AgentTaskScheduler(PROFILER_RECORDING_SCHEDULER);

  @Mock private ThreadLocalRandom threadLocalRandom;
  @Mock private ConfigProvider configProvider;
  @Mock private Controller controller;

  @Mock private ControllerContext.Snapshot context;
  @Mock private OngoingRecording recording;
  @Mock private RecordingData recordingData;
  @Mock private RecordingDataListener listener;

  private Appender<ILoggingEvent> mockedAppender;

  @SuppressWarnings("unchecked")
  @BeforeEach
  public void setup() throws Exception {
    assumeFalse(
        JavaVirtualMachine.isOracleJDK8(),
        "Oracle JDK 1.8 did not merge the fix in JDK-8058322, leading to the JVM failing to correctly "
            + "extract method parameters without args, when the code is compiled on a later JDK (targeting 8). "
            + "This can manifest when creating mocks.");

    when(controller.createRecording(ProfilingSystem.RECORDING_NAME, context)).thenReturn(recording);
    when(threadLocalRandom.nextInt(eq(1), anyInt())).thenReturn(1);
    when(recordingData.getEnd()).thenAnswer(mockInvocation -> Instant.now());

    mockedAppender = (Appender<ILoggingEvent>) Mockito.mock(Appender.class);
    ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
        .addAppender(mockedAppender);
  }

  @AfterEach
  public void tearDown() {
    scheduler.shutdown(5, SECONDS);
    ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
        .detachAppender(mockedAppender);
  }

  private void assertLog(Level level, String message) {
    ArgumentCaptor<ILoggingEvent> argumentCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
    Mockito.verify(mockedAppender, VerificationModeFactory.atLeastOnce())
        .doAppend(argumentCaptor.capture());

    for (ILoggingEvent event : argumentCaptor.getAllValues()) {
      if (message.contains(event.getFormattedMessage()) && level.equals(event.getLevel())) {
        return;
      }
    }
    fail("Log does not contain the expected message '" + message + "' at level '" + level + "'");
  }

  @Test
  void testInvalidOracleJdk() throws Exception {
    // Simulate the message part
    when(controller.createRecording(ProfilingSystem.RECORDING_NAME, context))
        .thenThrow(
            new RuntimeException(new RuntimeException("com.oracle.jrockit:type=FlightRecorder")));
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(1),
            true);
    system.start();
    assertLog(
        Level.WARN,
        "You're running Oracle JDK 8. Datadog Continuous Profiler for Java depends on Java Flight Recorder, which requires a paid license in Oracle JDK 8. If you have one, please add the following `java` command line args: ‘-XX:+UnlockCommercialFeatures -XX:+FlightRecorder’. Alternatively, you can use a different Java 8 distribution like OpenJDK, where Java Flight Recorder is free.");
  }

  @Test
  void testRuntimeException() throws Exception {
    // Simulate the message part
    when(controller.createRecording(ProfilingSystem.RECORDING_NAME, context))
        .thenThrow(new RuntimeException(new RuntimeException()));
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(1),
            true);
    assertThrows(RuntimeException.class, () -> system.start());
  }

  @Test
  void testOtherException() throws Exception {
    // Simulate the message part
    when(controller.createRecording(ProfilingSystem.RECORDING_NAME, context))
        .thenThrow(new IllegalArgumentException());
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(1),
            true);
    assertThrows(IllegalArgumentException.class, () -> system.start());
  }

  @Test
  public void testShutdown() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(300),
            false,
            scheduler,
            threadLocalRandom);
    startProfilingSystem(system);
    verify(controller).createRecording(any(), any());
    system.shutdown();

    verify(recording).close();
    assertTrue(scheduler.isShutdown());
  }

  @Test
  public void testShutdownWithRunningProfilingRecording() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(300),
            false,
            scheduler,
            threadLocalRandom);
    startProfilingSystem(system);
    verify(controller).createRecording(any(), any());
    system.shutdown();

    verify(recording).close();
    assertTrue(scheduler.isShutdown());
  }

  @Test
  public void testShutdownWithSnapshotOnShutdown() throws Exception {
    final ProfilingSystem system =
        spy(
            new ProfilingSystem(
                configProvider,
                controller,
                context,
                listener,
                Duration.ofMillis(10),
                Duration.ZERO,
                Duration.ofMillis(300),
                false,
                scheduler,
                threadLocalRandom));
    final ProfilingSystem.SnapshotRecording snapshotRecording =
        spy(system.createSnapshotRecording(Instant.now()));

    when(system.createSnapshotRecording(any())).thenReturn(snapshotRecording);
    startProfilingSystem(system);
    verify(controller).createRecording(any(), any());
    system.shutdown(true);

    verify(snapshotRecording).snapshot(true);
    verify(recording).close();
    assertTrue(scheduler.isShutdown());
  }

  @Test
  public void testForceEarlySTartup() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(300),
            true,
            scheduler,
            threadLocalRandom);
    system.start();
    assertTrue(system.isStarted());
    verify(controller).createRecording(any(), any());
  }

  @Test
  public void testShutdownInterruption() throws Exception {
    final Thread mainThread = Thread.currentThread();
    doAnswer(
            (InvocationOnMock invocation) -> {
              while (!scheduler.isShutdown()) {
                try {
                  Thread.sleep(100);
                } catch (final InterruptedException e) {
                  // Ignore InterruptedException to make sure this threads lives through executor
                  // shutdown
                }
              }
              // Interrupting main thread to make sure this is handled properly
              mainThread.interrupt();
              return null;
            })
        .when(listener)
        .onNewData(any(), any(), anyBoolean());
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(100),
            false,
            scheduler,
            threadLocalRandom);
    startProfilingSystem(system);
    // Make sure we actually started the recording before terminating
    verify(controller, timeout(300)).createRecording(any(), any());
    system.shutdown();
    assertTrue(true, "Shutdown exited cleanly after interruption");
  }

  @Test
  public void testCanShutDownWithoutStarting() throws ConfigurationException {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(300),
            false,
            scheduler,
            threadLocalRandom);
    system.shutdown();
    assertTrue(scheduler.isShutdown());
  }

  @Test
  public void testDoesntSendDataIfNotStarted() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(1),
            false);
    Thread.sleep(50);
    system.shutdown();
    verify(controller, never()).createRecording(any(), any());
    verify(listener, never()).onNewData(any(), any(), anyBoolean());
  }

  @Test
  public void testDoesntSendPeriodicRecordingIfPeriodicRecordingIsDisabled()
      throws InterruptedException, ConfigurationException {
    when(recording.snapshot(any(), any())).thenReturn(recordingData);
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(10),
            false);
    startProfilingSystem(system);
    Thread.sleep(200);
    system.shutdown();
    verify(listener, atLeastOnce()).onNewData(CONTINUOUS, recordingData, false);
  }

  @Test
  public void testProfilingSystemNegativeStartupDelay() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              configProvider,
              controller,
              context,
              listener,
              Duration.ofMillis(-10),
              Duration.ZERO,
              Duration.ofMillis(200),
              false);
        });
  }

  @Test
  public void testProfilingSystemNegativeStartupRandomRangeDelay() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              configProvider,
              controller,
              context,
              listener,
              Duration.ofMillis(10),
              Duration.ofMillis(-20),
              Duration.ofMillis(200),
              false);
        });
  }

  @Test
  public void testProfilingSystemNegativeUploadPeriod() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              configProvider,
              controller,
              context,
              listener,
              Duration.ofMillis(10),
              Duration.ofMillis(20),
              Duration.ofMillis(-200),
              false);
        });
  }

  /** Ensure that we continue recording after one recording fails to get created */
  @Test
  public void testRecordingSnapshotError() throws ConfigurationException {
    final Duration uploadPeriod = Duration.ofMillis(300);
    final List<RecordingData> generatedRecordingData = new ArrayList<>();
    when(recording.snapshot(any(), any()))
        .thenThrow(new RuntimeException("Test"))
        .thenAnswer(generateMockRecordingData(generatedRecordingData));

    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            uploadPeriod,
            false,
            scheduler,
            threadLocalRandom);
    startProfilingSystem(system);

    final ArgumentCaptor<RecordingData> captor = ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).atLeast(2))
        .onNewData(eq(CONTINUOUS), captor.capture(), anyBoolean());
    assertEquals(generatedRecordingData, captor.getAllValues());

    system.shutdown();
  }

  @Test
  public void testRecordingSnapshotNoData() throws ConfigurationException {
    final Duration uploadPeriod = Duration.ofMillis(300);
    final List<RecordingData> generatedRecordingData = new ArrayList<>();
    when(recording.snapshot(any(), any()))
        .thenReturn(null)
        .thenAnswer(generateMockRecordingData(generatedRecordingData));

    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            uploadPeriod,
            false,
            scheduler,
            threadLocalRandom);
    startProfilingSystem(system);

    final ArgumentCaptor<RecordingData> captor = ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(2))
        .onNewData(eq(CONTINUOUS), captor.capture(), anyBoolean());
    assertEquals(generatedRecordingData, captor.getAllValues());

    system.shutdown();
  }

  @Test
  public void testRandomizedStartupDelay() throws ConfigurationException {
    final Duration startupDelay = Duration.ofMillis(100);
    final Duration startupDelayRandomRange = Duration.ofMillis(500);
    final Duration additionalRandomDelay = Duration.ofMillis(300);

    when(threadLocalRandom.nextLong(startupDelayRandomRange.toMillis()))
        .thenReturn(additionalRandomDelay.toMillis());

    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            startupDelay,
            startupDelayRandomRange,
            Duration.ofMillis(100),
            false,
            scheduler,
            threadLocalRandom);

    final Duration randomizedDelay = system.getStartupDelay();

    assertEquals(startupDelay.plus(additionalRandomDelay), randomizedDelay);
  }

  @Test
  public void testFixedStartupDelay() throws ConfigurationException {
    final Duration startupDelay = Duration.ofMillis(100);

    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            startupDelay,
            Duration.ZERO,
            Duration.ofMillis(100),
            false,
            scheduler,
            threadLocalRandom);

    assertEquals(startupDelay, system.getStartupDelay());
  }

  @Test
  public void testEarlyShutdownException() throws Exception {
    when(controller.createRecording(any(), any()))
        .thenThrow(new IllegalStateException("Shutdown in progress"));

    final Duration startupDelay = Duration.ofMillis(1);

    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controller,
            context,
            listener,
            startupDelay,
            Duration.ZERO,
            Duration.ofMillis(100),
            false,
            scheduler,
            threadLocalRandom);

    system.start();
    Thread.sleep(200);
    // start will be aborted, but the IllegalStateException won't bubble up
    assertFalse(system.isStarted());
    system.shutdown();
  }

  private Answer<Object> generateMockRecordingData(
      final List<RecordingData> generatedRecordingData) {
    return (InvocationOnMock invocation) -> {
      final RecordingData recordingData = mock(RecordingData.class);
      when(recordingData.getStart()).thenReturn(invocation.getArgument(0, Instant.class));
      when(recordingData.getEnd()).thenAnswer(mockInvocation -> Instant.now());
      generatedRecordingData.add(recordingData);
      return recordingData;
    };
  }

  private void startProfilingSystem(final ProfilingSystem system) {
    system.start();
    await().until(system::isStarted);
  }
}
