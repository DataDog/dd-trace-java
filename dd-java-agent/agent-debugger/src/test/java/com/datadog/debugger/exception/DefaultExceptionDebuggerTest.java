package com.datadog.debugger.exception;

import static com.datadog.debugger.exception.DefaultExceptionDebugger.SNAPSHOT_ID_TAG_FMT;
import static com.datadog.debugger.util.TestHelper.assertWithTimeout;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.debugger.agent.ConfigurationAcceptor;
import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.ExceptionHelper;
import com.datadog.debugger.util.TestSnapshotListener;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultExceptionDebuggerTest {

  private ClassNameFiltering classNameFiltering;
  private ConfigurationUpdater configurationUpdater;
  private DefaultExceptionDebugger exceptionDebugger;
  private TestSnapshotListener listener;

  @BeforeEach
  public void setUp() {
    configurationUpdater = mock(ConfigurationUpdater.class);
    classNameFiltering = new ClassNameFiltering(emptyList());
    exceptionDebugger = new DefaultExceptionDebugger(configurationUpdater, classNameFiltering);
    listener = new TestSnapshotListener(createConfig(), mock(ProbeStatusSink.class));
    DebuggerAgentHelper.injectSink(listener);
  }

  @Test
  public void simpleException() {
    RuntimeException exception = new RuntimeException("test");
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    AgentSpan span = mock(AgentSpan.class);
    exceptionDebugger.handleException(exception, span);
    assertWithTimeout(
        () -> exceptionDebugger.getExceptionProbeManager().isAlreadyInstrumented(fingerprint),
        Duration.ofSeconds(30));
    exceptionDebugger.handleException(exception, span);
    verify(configurationUpdater).accept(eq(ConfigurationAcceptor.Source.EXCEPTION), any());
  }

  @Test
  public void nestedException() {
    RuntimeException exception = createNestException();
    AgentSpan span = mock(AgentSpan.class);
    Map<String, String> tags = new HashMap<>();
    doAnswer(
            invocationOnMock -> {
              Object[] args = invocationOnMock.getArguments();
              String key = (String) args[0];
              String value = (String) args[1];
              tags.put(key, value);
              return null;
            })
        .when(span)
        .setTag(anyString(), anyString());
    exceptionDebugger.handleException(exception, span);
    generateSnapshots(exception);
    exception.printStackTrace();
    exceptionDebugger.handleException(exception, span);
    ExceptionProbeManager.ThrowableState state =
        exceptionDebugger
            .getExceptionProbeManager()
            .getSateByThrowable(ExceptionHelper.getInnerMostThrowable(exception));
    assertEquals(
        state.getExceptionId(), tags.get(DefaultExceptionDebugger.DD_DEBUG_ERROR_EXCEPTION_ID));
    Map<String, Snapshot> snapshotMap =
        listener.snapshots.stream().collect(toMap(Snapshot::getId, Function.identity()));
    List<String> lines = parseStackTrace(exception);
    int expectedFrameIndex =
        findFrameIndex(
            lines,
            "com.datadog.debugger.exception.DefaultExceptionDebuggerTest.createNestException");
    assertSnapshot(
        tags,
        snapshotMap,
        expectedFrameIndex,
        "com.datadog.debugger.exception.DefaultExceptionDebuggerTest",
        "createNestException");
    expectedFrameIndex =
        findFrameIndex(
            lines, "com.datadog.debugger.exception.DefaultExceptionDebuggerTest.nestedException");
    assertSnapshot(
        tags,
        snapshotMap,
        expectedFrameIndex,
        "com.datadog.debugger.exception.DefaultExceptionDebuggerTest",
        "nestedException");
    expectedFrameIndex =
        findFrameIndex(
            lines,
            "com.datadog.debugger.exception.DefaultExceptionDebuggerTest.createTest1Exception");
    assertSnapshot(
        tags,
        snapshotMap,
        expectedFrameIndex,
        "com.datadog.debugger.exception.DefaultExceptionDebuggerTest",
        "createTest1Exception");
  }

  private static int findFrameIndex(List<String> lines, String str) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(str)) {
        return i;
      }
    }
    return -1;
  }

  private static List<String> parseStackTrace(RuntimeException exception) {
    StringWriter writer = new StringWriter();
    exception.printStackTrace(new PrintWriter(writer));
    writer.flush();
    BufferedReader reader = new BufferedReader(new StringReader(writer.toString()));
    List<String> results = new ArrayList<>();
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("\tat ")) {
          results.add(line);
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return results;
  }

  private static void assertSnapshot(
      Map<String, String> tags,
      Map<String, Snapshot> snapshotMap,
      int frameIndex,
      String className,
      String methodName) {
    String snapshotId = tags.get(String.format(SNAPSHOT_ID_TAG_FMT, frameIndex));
    Snapshot snapshot = snapshotMap.get(snapshotId);
    assertEquals(className, snapshot.getProbe().getLocation().getType());
    assertEquals(methodName, snapshot.getProbe().getLocation().getMethod());
  }

  private void generateSnapshots(RuntimeException exception) {
    BinaryOperator<ExceptionProbe> dropMerger = (oldValue, newValue) -> oldValue;
    Map<String, ExceptionProbe> probesByLocation =
        exceptionDebugger.getExceptionProbeManager().getProbes().stream()
            .collect(
                toMap(
                    probe ->
                        probe.getWhere().getTypeName()
                            + "::"
                            + probe.getWhere().getMethodName()
                            + ":"
                            + probe.getWhere().getLines()[0],
                    Function.identity(),
                    dropMerger));
    Throwable innerMost = ExceptionHelper.getInnerMostThrowable(exception);
    for (StackTraceElement element : innerMost.getStackTrace()) {
      ExceptionProbe exceptionProbe =
          probesByLocation.get(
              element.getClassName()
                  + "::"
                  + element.getMethodName()
                  + ":"
                  + element.getLineNumber());
      if (exceptionProbe == null) {
        continue;
      }
      exceptionProbe.buildLocation(null);
      CapturedContext capturedContext = new CapturedContext();
      capturedContext.addThrowable(exception);
      capturedContext.evaluate(
          exceptionProbe.getProbeId().getEncodedId(),
          exceptionProbe,
          "",
          System.currentTimeMillis(),
          MethodLocation.EXIT);
      exceptionProbe.commit(CapturedContext.EMPTY_CAPTURING_CONTEXT, capturedContext, emptyList());
    }
  }

  private RuntimeException createNestException() {
    return new RuntimeException("test3", createTest2Exception(createTest1Exception()));
  }

  private RuntimeException createTest1Exception() {
    return new RuntimeException("test1");
  }

  private RuntimeException createTest2Exception(Throwable cause) {
    return new RuntimeException("test2", cause);
  }

  private static Config createConfig() {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.isDebuggerVerifyByteCode()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    return config;
  }
}
