package com.datadog.debugger.exception;

import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.TestSnapshotListener;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FailedTestReplayExceptionDebuggerTest {

  private ClassNameFiltering classNameFiltering;
  private ConfigurationUpdater configurationUpdater;
  private FailedTestReplayExceptionDebugger exceptionDebugger;
  private TestSnapshotListener listener;

  @BeforeEach
  public void setUp() {
    configurationUpdater = mock(ConfigurationUpdater.class);
    classNameFiltering =
        new ClassNameFiltering(
            new HashSet<>(singletonList("com.datadog.debugger.exception.ThirdPartyCode")));
    Config config = createConfig();
    exceptionDebugger =
        new FailedTestReplayExceptionDebugger(configurationUpdater, classNameFiltering, config);
    listener = new TestSnapshotListener(createConfig(), mock(ProbeStatusSink.class));
    DebuggerAgentHelper.injectSink(listener);
  }

  @Test
  public void failedTestReplayModeWithoutActiveTest() {
    // other execution path is tested with smoke tests
    ExceptionProbeManager manager = mock(ExceptionProbeManager.class);
    exceptionDebugger.handleException(new RuntimeException("test"), mock(AgentSpan.class));
    verify(manager, times(0)).isAlreadyInstrumented(any());
  }

  public static Config createConfig() {
    Config config = mock(Config.class);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    when(config.getDebuggerExceptionCaptureInterval()).thenReturn(3600);
    when(config.getDebuggerMaxExceptionPerSecond()).thenReturn(1);
    when(config.getDebuggerExceptionMaxCapturedFrames()).thenReturn(3);
    return config;
  }
}
