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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.AgentTaskScheduler;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
  private final ControllerFactory controllerFactory = context -> controller;

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

    when(controller.createRecording(eq(ProfilingSystem.RECORDING_NAME), any()))
        .thenReturn(recording);
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
    when(controller.createRecording(eq(ProfilingSystem.RECORDING_NAME), any()))
        .thenThrow(
            new RuntimeException(new RuntimeException("com.oracle.jrockit:type=FlightRecorder")));
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
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
    when(controller.createRecording(eq(ProfilingSystem.RECORDING_NAME), any()))
        .thenThrow(new RuntimeException(new RuntimeException()));
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
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
    when(controller.createRecording(eq(ProfilingSystem.RECORDING_NAME), any()))
        .thenThrow(new IllegalArgumentException());
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
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
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(300),
            false,
            () -> scheduler,
            threadLocalRandom);
    startProfilingSystem(system);
    verify(controller).createRecording(any(), any());
    system.stop();

    verify(recording).close();
    assertTrue(scheduler.isShutdown());
  }

  @Test
  public void testShutdownWithRunningProfilingRecording() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(300),
            false,
            () -> scheduler,
            threadLocalRandom);
    startProfilingSystem(system);
    verify(controller).createRecording(any(), any());
    system.stop();

    verify(recording).close();
    assertTrue(scheduler.isShutdown());
  }

  @Test
  public void testShutdownWithSnapshotOnShutdown() throws Exception {
    final ProfilingSystem system =
        spy(
            new ProfilingSystem(
                configProvider,
                controllerFactory,
                listener,
                Duration.ofMillis(10),
                Duration.ZERO,
                Duration.ofMillis(300),
                false,
                () -> scheduler,
                threadLocalRandom));
    final ProfilingSystem.SnapshotRecording snapshotRecording =
        spy(system.createSnapshotRecording(recording, Instant.now()));

    when(system.createSnapshotRecording(any(), any())).thenReturn(snapshotRecording);
    startProfilingSystem(system);
    verify(controller).createRecording(any(), any());
    system.shutdown(true);

    verify(snapshotRecording).snapshot(ProfilingSnapshot.Kind.ON_SHUTDOWN, true);
    verify(recording).close();
    assertTrue(scheduler.isShutdown());
  }

  @Test
  public void testStopSkipsTheFinalSnapshotWhenTheSchedulerDidNotTerminate() throws Exception {
    // as if its thread were still taking a periodic snapshot after the termination timeout
    final AgentTaskScheduler stuckScheduler =
        new AgentTaskScheduler(PROFILER_RECORDING_SCHEDULER) {
          @Override
          public boolean isTerminated() {
            return false;
          }
        };
    final ProfilingSystem system =
        spy(
            new ProfilingSystem(
                configProvider,
                controllerFactory,
                listener,
                Duration.ofMillis(10),
                Duration.ZERO,
                Duration.ofMillis(300),
                true,
                () -> stuckScheduler,
                threadLocalRandom));
    final ProfilingSystem.SnapshotRecording snapshotRecording =
        spy(system.createSnapshotRecording(recording, Instant.now()));
    when(system.createSnapshotRecording(any(), any())).thenReturn(snapshotRecording);
    when(recording.stop()).thenReturn(recordingData);

    system.start();
    system.stop(true);

    verify(snapshotRecording, never()).snapshot(any(), anyBoolean());
    verify(recording).stop();
    assertFalse(system.isStarted());
  }

  @Test
  public void testRestartBeforeTheStartupDelayKeepsItsDeadline() throws Exception {
    // the delay of each delayed start, from when it was scheduled
    final List<Long> startDelaysNanos = new ArrayList<>();
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofHours(1),
            Duration.ZERO,
            Duration.ofMillis(300),
            false,
            () ->
                new AgentTaskScheduler(PROFILER_RECORDING_SCHEDULER) {
                  @Override
                  public <T> Scheduled<T> schedule(
                      Task<T> task, T target, long initialDelay, TimeUnit unit) {
                    startDelaysNanos.add(unit.toNanos(initialDelay));
                    return super.schedule(task, target, initialDelay, unit);
                  }
                },
            threadLocalRandom);
    try {
      system.start(false);
      // so that the deadline of the first start is measurably closer when restarting
      Thread.sleep(10);
      system.stop(false);
      system.start(false);

      assertEquals(2, startDelaysNanos.size(), String.valueOf(startDelaysNanos));
      // a new delay from the restart would be the whole hour again, not shorter than the first
      assertTrue(
          startDelaysNanos.get(1) < startDelaysNanos.get(0),
          "the restart waits for the deadline of the first start: " + startDelaysNanos);
      verify(controller, never()).createRecording(any(), any());
    } finally {
      system.shutdown(false);
    }
  }

  /**
   * The snapshot callback of a stopped recording, run by a scheduler thread that outlived the stop.
   */
  @Test
  public void testSnapshotOfAStoppedRecordingIsSkipped() throws Exception {
    final List<Runnable> snapshotCallbacks = new ArrayList<>();
    final ProfilingSystem system = systemWithCapturedSnapshotCallbacks(snapshotCallbacks);
    system.start();
    assertEquals(1, snapshotCallbacks.size());
    final Runnable callback = snapshotCallbacks.get(0);
    // it snapshots its recording while it runs
    callback.run();
    verify(recording).snapshot(any(), any());

    system.stop(false);
    // as from a scheduler thread that outlived the stop
    callback.run();

    verify(recording, times(1)).snapshot(any(), any());
    verify(listener, never()).onNewData(any(), any(), anyBoolean());
  }

  /**
   * A snapshot callback of a scheduler thread that outlived its stop doesn't snapshot the recording
   * of a restart after it, which has its own callback.
   */
  @Test
  public void testSnapshotOfARecordingReplacedByARestartIsSkipped() throws Exception {
    final OngoingRecording restartedRecording = mock(OngoingRecording.class);
    when(controller.createRecording(eq(ProfilingSystem.RECORDING_NAME), any()))
        .thenReturn(recording, restartedRecording);
    final List<Runnable> snapshotCallbacks = new ArrayList<>();
    final ProfilingSystem system = systemWithCapturedSnapshotCallbacks(snapshotCallbacks);
    try {
      system.start();
      system.stop(false);
      system.start();
      assertTrue(system.isStarted());
      assertEquals(2, snapshotCallbacks.size());

      // as from the first scheduler thread, resuming after the restart
      snapshotCallbacks.get(0).run();

      verify(restartedRecording, never()).snapshot(any(), any());
      verify(recording, never()).snapshot(any(), any());
      verify(listener, never()).onNewData(any(), any(), anyBoolean());

      // while the callback of the restart snapshots its recording
      snapshotCallbacks.get(1).run();
      verify(restartedRecording).snapshot(any(), any());
    } finally {
      system.shutdown(false);
    }
  }

  /**
   * A system that starts its recordings right away, with schedulers that keep the periodic snapshot
   * callbacks instead of running them, so that a test runs them when it wants to.
   */
  private ProfilingSystem systemWithCapturedSnapshotCallbacks(final List<Runnable> callbacks)
      throws Exception {
    return new ProfilingSystem(
        configProvider,
        controllerFactory,
        listener,
        Duration.ofMillis(10),
        Duration.ZERO,
        Duration.ofMillis(300),
        true,
        () ->
            new AgentTaskScheduler(PROFILER_RECORDING_SCHEDULER) {
              @Override
              public <T> Scheduled<T> scheduleAtFixedRate(
                  Task<T> task, T target, long initialDelay, long period, TimeUnit unit) {
                callbacks.add(() -> task.run(target));
                return null;
              }
            },
        threadLocalRandom);
  }

  @Test
  public void testStopWithSnapshotTakesAPeriodicOne() throws Exception {
    final ProfilingSystem system =
        spy(
            new ProfilingSystem(
                configProvider,
                controllerFactory,
                listener,
                Duration.ofMillis(10),
                Duration.ZERO,
                Duration.ofMillis(300),
                false,
                () -> scheduler,
                threadLocalRandom));
    final ProfilingSystem.SnapshotRecording snapshotRecording =
        spy(system.createSnapshotRecording(recording, Instant.now()));

    when(system.createSnapshotRecording(any(), any())).thenReturn(snapshotRecording);
    startProfilingSystem(system);
    verify(controller).createRecording(any(), any());
    system.stop(true);

    verify(snapshotRecording).snapshot(ProfilingSnapshot.Kind.PERIODIC, true);
    verify(recording).close();
    assertTrue(scheduler.isShutdown());
  }

  @Test
  public void testForceEarlySTartup() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(300),
            true,
            () -> scheduler,
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
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(100),
            false,
            () -> scheduler,
            threadLocalRandom);
    startProfilingSystem(system);
    // Make sure we actually started the recording before terminating
    verify(controller, timeout(300)).createRecording(any(), any());
    system.stop();
    assertTrue(true, "Shutdown exited cleanly after interruption");
  }

  @Test
  public void testCanStopWithoutStarting() throws ConfigurationException {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(300),
            false,
            () -> scheduler,
            threadLocalRandom);
    system.stop();
    assertFalse(system.isStarted());
    assertFalse(scheduler.isShutdown(), "the scheduler is only used once started");
  }

  @Test
  public void testDoesntSendDataIfNotStarted() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(1),
            false);
    Thread.sleep(50);
    system.stop();
    verify(controller, never()).createRecording(any(), any());
    verify(listener, never()).onNewData(any(), any(), anyBoolean());
  }

  @Test
  public void testDoesntSendPeriodicRecordingIfPeriodicRecordingIsDisabled() throws Exception {
    when(recording.snapshot(any(), any())).thenReturn(recordingData);
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            Duration.ofMillis(10),
            false);
    startProfilingSystem(system);
    Thread.sleep(200);
    system.stop();
    verify(listener, atLeastOnce()).onNewData(CONTINUOUS, recordingData, false);
  }

  @Test
  public void testProfilingSystemNegativeStartupDelay() {
    assertThrows(
        ConfigurationException.class,
        () -> {
          new ProfilingSystem(
              configProvider,
              controllerFactory,
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
              controllerFactory,
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
              controllerFactory,
              listener,
              Duration.ofMillis(10),
              Duration.ofMillis(20),
              Duration.ofMillis(-200),
              false);
        });
  }

  /** Ensure that we continue recording after one recording fails to get created */
  @Test
  public void testRecordingSnapshotError() throws Exception {
    final Duration uploadPeriod = Duration.ofMillis(300);
    final List<RecordingData> generatedRecordingData = new ArrayList<>();
    when(recording.snapshot(any(), any()))
        .thenThrow(new RuntimeException("Test"))
        .thenAnswer(generateMockRecordingData(generatedRecordingData));

    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            uploadPeriod,
            false,
            () -> scheduler,
            threadLocalRandom);
    startProfilingSystem(system);

    final ArgumentCaptor<RecordingData> captor = ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).atLeast(2))
        .onNewData(eq(CONTINUOUS), captor.capture(), anyBoolean());
    assertEquals(generatedRecordingData, captor.getAllValues());

    system.stop();
  }

  @Test
  public void testRecordingSnapshotNoData() throws Exception {
    final Duration uploadPeriod = Duration.ofMillis(300);
    final List<RecordingData> generatedRecordingData = new ArrayList<>();
    when(recording.snapshot(any(), any()))
        .thenReturn(null)
        .thenAnswer(generateMockRecordingData(generatedRecordingData));

    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ofMillis(5),
            uploadPeriod,
            false,
            () -> scheduler,
            threadLocalRandom);
    startProfilingSystem(system);

    final ArgumentCaptor<RecordingData> captor = ArgumentCaptor.forClass(RecordingData.class);
    verify(listener, timeout(REASONABLE_TIMEOUT).times(2))
        .onNewData(eq(CONTINUOUS), captor.capture(), anyBoolean());
    assertEquals(generatedRecordingData, captor.getAllValues());

    system.stop();
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
            controllerFactory,
            listener,
            startupDelay,
            startupDelayRandomRange,
            Duration.ofMillis(100),
            false,
            () -> scheduler,
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
            controllerFactory,
            listener,
            startupDelay,
            Duration.ZERO,
            Duration.ofMillis(100),
            false,
            () -> scheduler,
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
            controllerFactory,
            listener,
            startupDelay,
            Duration.ZERO,
            Duration.ofMillis(100),
            false,
            () -> scheduler,
            threadLocalRandom);

    system.start();
    Thread.sleep(200);
    // start will be aborted, but the IllegalStateException won't bubble up
    assertFalse(system.isStarted());
    system.stop();
  }

  @Test
  public void testInitCreatesControllerWithoutRecording() throws Exception {
    final AtomicInteger controllersCreated = new AtomicInteger();
    final AtomicInteger schedulersCreated = new AtomicInteger();
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            context -> {
              controllersCreated.incrementAndGet();
              return controller;
            },
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> {
              schedulersCreated.incrementAndGet();
              return scheduler;
            },
            threadLocalRandom);

    system.init();
    system.init();

    assertEquals(1, controllersCreated.get());
    assertEquals(0, schedulersCreated.get());
    assertFalse(system.isStarted());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  public void testInitFailure() throws Exception {
    final UnsupportedEnvironmentException unsupported =
        new UnsupportedEnvironmentException("unsupported");
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            context -> {
              throw unsupported;
            },
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> scheduler,
            threadLocalRandom);

    assertSame(unsupported, assertThrows(UnsupportedEnvironmentException.class, system::init));
    assertSame(unsupported, assertThrows(UnsupportedEnvironmentException.class, system::start));
    assertFalse(system.isStarted());
  }

  @Test
  public void testInitPassesPopulatedContextToRecording() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            context -> {
              context.setDatadogProfilerEnabled(true);
              return controller;
            },
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> scheduler,
            threadLocalRandom);

    system.start();

    final ArgumentCaptor<ControllerContext.Snapshot> snapshot =
        ArgumentCaptor.forClass(ControllerContext.Snapshot.class);
    verify(controller).createRecording(eq(ProfilingSystem.RECORDING_NAME), snapshot.capture());
    assertTrue(snapshot.getValue().isDatadogProfilerEnabled());
    system.stop();
  }

  @Test
  public void testStartWhileStartedIsNoop() throws Exception {
    final AtomicInteger schedulersCreated = new AtomicInteger();
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> {
              schedulersCreated.incrementAndGet();
              return scheduler;
            },
            threadLocalRandom);

    system.start();
    system.start();

    assertEquals(1, schedulersCreated.get());
    verify(controller).createRecording(any(), any());
    system.stop();
  }

  @Test
  public void testStopStopsRecordingAndCanRestart() throws Exception {
    when(recording.stop()).thenReturn(recordingData);
    final AtomicInteger controllersCreated = new AtomicInteger();
    final List<AgentTaskScheduler> schedulers = new ArrayList<>();
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            context -> {
              controllersCreated.incrementAndGet();
              return controller;
            },
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> {
              AgentTaskScheduler newScheduler =
                  new AgentTaskScheduler(PROFILER_RECORDING_SCHEDULER);
              schedulers.add(newScheduler);
              return newScheduler;
            },
            threadLocalRandom);

    system.start();
    assertTrue(system.isStarted());
    system.stop();

    assertFalse(system.isStarted());
    assertTrue(schedulers.get(0).isShutdown());
    verify(recording).stop();
    verify(recordingData).release();
    verify(recording).close();

    system.start();
    assertTrue(system.isStarted());
    assertEquals(2, schedulers.size());
    assertFalse(schedulers.get(1).isShutdown());
    assertEquals(1, controllersCreated.get(), "the controller is kept across restarts");
    verify(controller, times(2)).createRecording(any(), any());

    system.stop();
    assertTrue(schedulers.get(1).isShutdown());
  }

  @Test
  public void testStopClosesRecordingWhenStopFails() throws Exception {
    when(recording.stop()).thenThrow(new IllegalStateException("not running"));
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> scheduler,
            threadLocalRandom);

    system.start();
    system.stop();

    verify(recording).close();
    assertFalse(system.isStarted());
  }

  @Test
  public void testFailedFirstStartTearsDownScheduler() throws Exception {
    when(controller.createRecording(any(), any()))
        .thenThrow(new IllegalArgumentException())
        .thenReturn(recording);
    final List<AgentTaskScheduler> schedulers = new ArrayList<>();
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> {
              AgentTaskScheduler newScheduler =
                  new AgentTaskScheduler(PROFILER_RECORDING_SCHEDULER);
              schedulers.add(newScheduler);
              return newScheduler;
            },
            threadLocalRandom);

    assertThrows(IllegalArgumentException.class, system::start);
    assertTrue(schedulers.get(0).isShutdown());
    assertFalse(system.isStarted());

    system.start();
    assertTrue(system.isStarted());
    system.stop();
  }

  @Test
  public void testUnsupportedEnvironmentOnRecording() throws Exception {
    when(controller.createRecording(any(), any()))
        .thenThrow(new UnsupportedEnvironmentException("unsupported"));
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> scheduler,
            threadLocalRandom);

    system.start();

    assertFalse(system.isStarted());
    system.stop();
  }

  @Test
  public void testErrorOnRecordingIsWrapped() throws Exception {
    final AssertionError error = new AssertionError("boom");
    when(controller.createRecording(any(), any())).thenThrow(error);
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> scheduler,
            threadLocalRandom);

    final RuntimeException thrown = assertThrows(RuntimeException.class, system::start);
    assertSame(error, thrown.getCause());
  }

  @Test
  public void testStoppedWhileCreatingRecording() throws Exception {
    when(controller.createRecording(any(), any()))
        .thenAnswer(invocation -> {
          // simulates stop() tearing down the scheduler while the recording is created
          scheduler.shutdown(0, SECONDS);
          return recording;
        });
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> scheduler,
            threadLocalRandom);

    system.start();

    assertFalse(system.isStarted());
    verify(recording).stop();
    verify(recording).close();
  }

  /**
   * A recording created after its scheduler was shut down is stopped by the scheduler thread
   * without holding the lock that stop() takes, so a stop whose wait for that thread timed out is
   * not held up by a native stop that hangs.
   */
  @Test
  public void testStopIsNotBlockedByTheStopOfARecordingCreatedAfterIt() throws Exception {
    final CountDownLatch creating = new CountDownLatch(1);
    final CountDownLatch created = new CountDownLatch(1);
    final CountDownLatch nativeStopReleased = new CountDownLatch(1);
    when(controller.createRecording(any(), any()))
        .thenAnswer(invocation -> {
          creating.countDown();
          awaitUninterruptibly(created);
          return recording;
        });
    when(recording.stop())
        .thenAnswer(invocation -> {
          awaitUninterruptibly(nativeStopReleased);
          return recordingData;
        });
    final ProfilingSystem system = systemWithShortTerminationTimeout();
    system.start();
    assertTrue(creating.await(10, SECONDS));

    final ExecutorService stopper = Executors.newSingleThreadExecutor();
    try {
      final Future<?> stop = stopper.submit(() -> system.stop(false));
      // the recording is created once the scheduler is shut down, so it isn't published
      await().atMost(10, SECONDS).until(scheduler::isShutdown);
      created.countDown();

      // stop() waits a bounded time for the hanging native stop instead of blocking on the lock
      stop.get(10, SECONDS);
      verify(recording).stop();
      verify(recording, never()).close();
    } finally {
      nativeStopReleased.countDown();
      stopper.shutdownNow();
    }
    verify(recording, timeout(10_000)).close();
  }

  /**
   * A stop waits for the scheduler thread to stop a recording that it created after the scheduler
   * was shut down, so that a restart can start a new one.
   */
  @Test
  public void testStopWaitsForTheStopOfARecordingCreatedAfterIt() throws Exception {
    final CountDownLatch creating = new CountDownLatch(1);
    final CountDownLatch created = new CountDownLatch(1);
    when(controller.createRecording(any(), any()))
        .thenAnswer(invocation -> {
          creating.countDown();
          awaitUninterruptibly(created);
          return recording;
        });
    when(recording.stop()).thenReturn(recordingData);
    final ProfilingSystem system = systemWithShortTerminationTimeout();
    system.terminationTimeoutMillis = 1_000;
    system.start();
    assertTrue(creating.await(10, SECONDS));

    final ExecutorService creator = Executors.newSingleThreadExecutor();
    try {
      creator.submit(() -> {
        // let the recording be created once stop() has shut the scheduler down and its wait
        // for the scheduler thread has timed out, so that stop() waits for the recording
        await().atMost(10, SECONDS).until(scheduler::isShutdown);
        Thread.sleep(1_500);
        created.countDown();
        return null;
      });

      system.stop(false);

      // already stopped and closed when stop() returns
      verify(recording).stop();
      verify(recording).close();
      assertFalse(system.isStarted());
    } finally {
      creator.shutdownNow();
    }
  }

  /** The scheduler join times out quickly, while the scheduler thread creates the recording. */
  private ProfilingSystem systemWithShortTerminationTimeout() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            false,
            () -> scheduler,
            threadLocalRandom);
    system.terminationTimeoutMillis = 500;
    return system;
  }

  /** The scheduler interrupts its thread when shut down, which must not end the waits. */
  private static void awaitUninterruptibly(final CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  public void testStopWithSnapshotBeforeRecording() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(200),
            Duration.ZERO,
            Duration.ofMillis(100),
            false,
            () -> scheduler,
            threadLocalRandom);

    system.start();
    system.stop(true);

    verify(listener, never()).onNewData(any(), any(), anyBoolean());
    assertTrue(scheduler.isShutdown());
  }

  @Test
  public void testStopDuringStartupDelayDoesNotRecord() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(200),
            Duration.ZERO,
            Duration.ofMillis(100),
            false,
            () -> scheduler,
            threadLocalRandom);

    system.start();
    system.stop();
    Thread.sleep(300);

    verify(controller, never()).createRecording(any(), any());
    assertFalse(system.isStarted());
  }

  @Test
  public void testShutdownIsFinalAndOnlyClosesTheRecording() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> scheduler,
            threadLocalRandom);
    system.start();
    assertTrue(system.isStarted());

    system.shutdown(false);

    assertFalse(system.isStarted());
    verify(recording).close();
    verify(recording, never()).stop();

    // a shut down system can't be started again, e.g. by remote config that is still in flight
    system.start();
    assertFalse(system.isStarted());
    assertFalse(system.isRunning());
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  public void testUnsupportedEnvironmentIsAStartFailureUntilStopped() throws Exception {
    when(controller.createRecording(any(), any()))
        .thenThrow(new UnsupportedEnvironmentException("unsupported"));
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            true,
            () -> scheduler,
            threadLocalRandom);
    assertFalse(system.isStartFailed());

    system.start();

    assertTrue(system.isStartFailed());
    system.stop(false);
    assertFalse(system.isStartFailed());
  }

  @Test
  public void testDelayedStartFailureIsRecorded() throws Exception {
    when(controller.createRecording(any(), any())).thenThrow(new IllegalArgumentException("boom"));
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ofMillis(10),
            Duration.ZERO,
            Duration.ofMillis(100),
            false,
            () -> scheduler,
            threadLocalRandom);

    // the start is delayed, so it doesn't throw: this is how a caller learns that it failed
    system.start();
    await().until(system::isStartFailed);

    assertFalse(system.isStarted());
    assertTrue(system.isRunning());
    system.stop(false);
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

  @Test
  public void testShutdownWithSnapshotWaitsForTheUploadOfAStopInProgress() throws Exception {
    CountDownLatch uploading = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    ProfilingSystem system = systemStoppingInTheBackground(uploading, proceed);
    final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
    final Thread shutdown =
        new Thread(
            () -> {
              try {
                system.shutdown(true);
              } catch (Throwable t) {
                shutdownFailure.set(t);
              }
            },
            "test-shutdown");
    shutdown.setDaemon(true);
    try {
      shutdown.start();

      // the shutdown waits for the stop in progress, which holds the lock of the system while it
      // uploads: observed, rather than assumed from the shutdown not being done yet
      await()
          .atMost(REASONABLE_TIMEOUT, MILLISECONDS)
          .until(() -> system.lock.hasQueuedThread(shutdown));
      assertTrue(shutdown.isAlive(), "waits for the upload of the stop in progress");
      proceed.countDown();
      shutdown.join(REASONABLE_TIMEOUT);
      assertFalse(shutdown.isAlive(), "done once the upload is");
      assertNull(shutdownFailure.get());
    } finally {
      proceed.countDown();
    }
    // the stop in progress took the only snapshot
    verify(recording, times(1)).snapshot(any(), any());
  }

  @Test
  public void testShutdownWithoutSnapshotDoesNotWaitForAStopInProgress() throws Exception {
    CountDownLatch uploading = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    try {
      ProfilingSystem system = systemStoppingInTheBackground(uploading, proceed);

      assertTimeoutPreemptively(Duration.ofSeconds(1), () -> system.shutdown(false));
    } finally {
      proceed.countDown();
    }
  }

  /** Like a stop, a shutdown with snapshot that is uploading doesn't hold up a shutdown hook. */
  @Test
  public void testShutdownWithoutSnapshotDoesNotWaitForAShutdownWithSnapshot() throws Exception {
    CountDownLatch uploading = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    try {
      ProfilingSystem system =
          systemStoppingInTheBackground(uploading, proceed, system1 -> system1.shutdown(true));

      assertTimeoutPreemptively(Duration.ofSeconds(1), () -> system.shutdown(false));
    } finally {
      proceed.countDown();
    }
  }

  /**
   * A shutdown without snapshot only skips its stop for a stop in progress: when the lock is held
   * for something else, it waits for it, and still closes the recording.
   */
  @Test
  public void testShutdownWithoutSnapshotWaitsForAnotherHolderOfTheLock() throws Exception {
    final ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofHours(1),
            true,
            () -> scheduler,
            threadLocalRandom);
    startProfilingSystem(system);
    final Thread shutdown = new Thread(() -> system.shutdown(false), "test-shutdown");
    shutdown.setDaemon(true);

    // as if it were checking whether the system is running
    system.lock.lock();
    try {
      shutdown.start();
      await()
          .atMost(REASONABLE_TIMEOUT, MILLISECONDS)
          .until(() -> system.lock.hasQueuedThread(shutdown));
      verify(recording, never()).close();
    } finally {
      system.lock.unlock();
    }
    shutdown.join(REASONABLE_TIMEOUT);

    assertFalse(shutdown.isAlive());
    verify(recording).close();
  }

  /**
   * A stop doesn't stop a recording while a snapshot of it is in progress, e.g. on a scheduler
   * thread that didn't terminate in time, nor waits for it: the snapshotting thread stops it once
   * the snapshot is done.
   */
  @Test
  public void testRecordingBeingSnapshotIsStoppedAfterTheSnapshotWithoutWaiting() throws Exception {
    final CountDownLatch snapshotting = new CountDownLatch(1);
    final CountDownLatch snapshotDone = new CountDownLatch(1);
    when(recording.snapshot(any(), any()))
        .thenAnswer(invocation -> {
          snapshotting.countDown();
          awaitUninterruptibly(snapshotDone);
          return recordingData;
        });
    final List<Runnable> snapshotCallbacks = new ArrayList<>();
    final ProfilingSystem system = systemWithCapturedSnapshotCallbacks(snapshotCallbacks);
    system.start();
    final Thread snapshot = new Thread(snapshotCallbacks.get(0), "test-snapshot");
    snapshot.setDaemon(true);
    try {
      snapshot.start();
      assertTrue(snapshotting.await(REASONABLE_TIMEOUT, MILLISECONDS), "snapshot in progress");

      // returns while the snapshot is still in progress
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> system.stop(false));
      assertFalse(system.isStarted());
      verify(recording, never()).stop();
      verify(recording, never()).close();
    } finally {
      snapshotDone.countDown();
    }
    snapshot.join(REASONABLE_TIMEOUT);

    assertFalse(snapshot.isAlive());
    InOrder inOrder = inOrder(recording);
    inOrder.verify(recording).snapshot(any(), any());
    inOrder.verify(recording).stop();
    inOrder.verify(recording).close();
  }

  /** A recording that isn't being snapshot is stopped by the stop itself. */
  @Test
  public void testStopStopsARecordingThatIsNotBeingSnapshot() throws Exception {
    final List<Runnable> snapshotCallbacks = new ArrayList<>();
    final ProfilingSystem system = systemWithCapturedSnapshotCallbacks(snapshotCallbacks);
    system.start();
    snapshotCallbacks.get(0).run();

    system.stop(false);

    InOrder inOrder = inOrder(recording);
    inOrder.verify(recording).snapshot(any(), any());
    inOrder.verify(recording).stop();
    inOrder.verify(recording).close();
  }

  /**
   * Starts a system, and stops it on another thread, which is uploading its last snapshot, until
   * {@code proceed} is counted down, once this returns.
   */
  private ProfilingSystem systemStoppingInTheBackground(
      CountDownLatch uploading, CountDownLatch proceed) throws Exception {
    return systemStoppingInTheBackground(uploading, proceed, system -> system.stop(true));
  }

  /** Like the other, with the given stop, e.g. a shutdown with snapshot. */
  private ProfilingSystem systemStoppingInTheBackground(
      CountDownLatch uploading, CountDownLatch proceed, Consumer<ProfilingSystem> stopping)
      throws Exception {
    when(recording.snapshot(any(), any())).thenReturn(recordingData);
    doAnswer(
            invocation -> {
              uploading.countDown();
              proceed.await(REASONABLE_TIMEOUT, MILLISECONDS);
              return null;
            })
        .when(listener)
        .onNewData(any(), any(), anyBoolean());
    ProfilingSystem system =
        new ProfilingSystem(
            configProvider,
            controllerFactory,
            listener,
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofHours(1),
            true,
            () -> scheduler,
            threadLocalRandom);
    startProfilingSystem(system);
    Thread stoppingThread = new Thread(() -> stopping.accept(system), "test-stop");
    stoppingThread.setDaemon(true);
    stoppingThread.start();
    assertTrue(uploading.await(REASONABLE_TIMEOUT, MILLISECONDS), "the stop is uploading");
    return system;
  }

  /** Whether the thread is blocked on entering a {@code synchronized} block on the object. */
  private static boolean isBlockedOnTheMonitorOf(final Thread thread, final Object object) {
    final ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
    return info != null
        && info.getThreadState() == Thread.State.BLOCKED
        && info.getLockInfo() != null
        && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(object);
  }

  private void startProfilingSystem(final ProfilingSystem system) throws Exception {
    system.start();
    await().until(system::isStarted);
  }
}
