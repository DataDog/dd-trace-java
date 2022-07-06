package com.datadog.debugger.poller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PollerSchedulerTest {
  @Mock Config config;

  @Mock ConfigurationPoller poller;

  @Test
  public void testReschedule() {
    when(config.getDebuggerPollInterval()).thenReturn(1);
    AgentTaskScheduler taskScheduler = mock(AgentTaskScheduler.class);
    when(taskScheduler.scheduleAtFixedRate(any(), any(), anyLong(), anyLong(), any()))
        .thenReturn(mock(AgentTaskScheduler.Scheduled.class));
    PollerScheduler scheduler = new PollerScheduler(config, poller, taskScheduler);
    scheduler.start();
    long expectedPollInterval = scheduler.getInitialPollInterval();
    verify(taskScheduler)
        .scheduleAtFixedRate(
            any(), any(), eq(0L), eq(expectedPollInterval), eq(TimeUnit.MILLISECONDS));
    expectedPollInterval = Duration.ofSeconds(2).toMillis();
    scheduler.reschedule(expectedPollInterval);
    verify(taskScheduler)
        .scheduleAtFixedRate(
            any(),
            any(),
            eq(expectedPollInterval),
            eq(expectedPollInterval),
            eq(TimeUnit.MILLISECONDS));
  }
}
