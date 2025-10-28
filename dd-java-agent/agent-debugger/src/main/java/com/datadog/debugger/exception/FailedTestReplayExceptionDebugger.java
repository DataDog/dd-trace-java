package com.datadog.debugger.exception;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link DebuggerContext.ExceptionDebugger} for CiVisibility's Failed Test Replay
 */
public class FailedTestReplayExceptionDebugger extends AbstractExceptionDebugger {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(FailedTestReplayExceptionDebugger.class);

  public static final String TEST_DEBUG_ERROR_FILE_TAG_FMT = DD_DEBUG_ERROR_PREFIX + "%d.file";
  public static final String TEST_DEBUG_ERROR_LINE_TAG_FMT = DD_DEBUG_ERROR_PREFIX + "%d.line";

  public FailedTestReplayExceptionDebugger(
      ConfigurationUpdater configurationUpdater,
      DebuggerContext.ClassNameFilter classNameFiltering,
      Config config) {
    super(
        new ExceptionProbeManager(classNameFiltering, Duration.ofSeconds(0)),
        configurationUpdater,
        classNameFiltering,
        config.getDebuggerExceptionMaxCapturedFrames(),
        false);
  }

  @Override
  protected boolean shouldHandleException(Throwable t, AgentSpan span) {
    TestContext testContext = InstrumentationTestBridge.getCurrentTestContext();
    if (testContext == null) {
      return false;
    }
    TestExecutionHistory executionHistory = testContext.get(TestExecutionHistory.class);
    return executionHistory != null && executionHistory.failedTestReplayApplicable();
  }

  @Override
  protected void addStackFrameTags(
      AgentSpan span, Snapshot snapshot, int frameIndex, StackTraceElement stackFrame) {
    super.addStackFrameTags(span, snapshot, frameIndex, stackFrame);

    String fileTag = String.format(TEST_DEBUG_ERROR_FILE_TAG_FMT, frameIndex);
    String lineTag = String.format(TEST_DEBUG_ERROR_LINE_TAG_FMT, frameIndex);
    span.setTag(fileTag, stackFrame.getFileName());
    span.setTag(lineTag, stackFrame.getLineNumber());

    LOGGER.debug(
        "add ftr debug tags to span[{}]: {}={}, {}={}",
        span.getSpanId(),
        fileTag,
        stackFrame.getFileName(),
        lineTag,
        stackFrame.getLineNumber());
  }
}
