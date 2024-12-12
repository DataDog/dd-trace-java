package com.datadog.debugger.exception;

import static com.datadog.debugger.exception.DefaultExceptionDebugger.SNAPSHOT_ID_TAG_FMT;
import static com.datadog.debugger.util.TestHelper.assertWithTimeout;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

public class DefaultExceptionDebuggerTest {

  private ClassNameFiltering classNameFiltering;
  private ConfigurationUpdater configurationUpdater;
  private DefaultExceptionDebugger exceptionDebugger;
  private TestSnapshotListener listener;
  private Map<String, Object> spanTags = new HashMap<>();

  @BeforeEach
  public void setUp() {
    configurationUpdater = mock(ConfigurationUpdater.class);
    classNameFiltering =
        new ClassNameFiltering(
            new HashSet<>(singletonList("com.datadog.debugger.exception.ThirdPartyCode")));
    exceptionDebugger =
        new DefaultExceptionDebugger(
            configurationUpdater, classNameFiltering, Duration.ofHours(1), 100);
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
  public void doubleHandleException() {
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
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    AgentSpan span = mock(AgentSpan.class);
    doAnswer(this::recordTags).when(span).setTag(anyString(), anyString());
    exceptionDebugger.handleException(exception, span);
    assertWithTimeout(
        () -> exceptionDebugger.getExceptionProbeManager().isAlreadyInstrumented(fingerprint),
        Duration.ofSeconds(30));
    generateSnapshots(exception);
    exception.printStackTrace();
    exceptionDebugger.handleException(exception, span);
    ExceptionProbeManager.ThrowableState state =
        exceptionDebugger
            .getExceptionProbeManager()
            .getStateByThrowable(ExceptionHelper.getInnerMostThrowable(exception));
    assertEquals(
        state.getExceptionId(), spanTags.get(DefaultExceptionDebugger.DD_DEBUG_ERROR_EXCEPTION_ID));
    Map<String, Snapshot> snapshotMap =
        listener.snapshots.stream().collect(toMap(Snapshot::getId, Function.identity()));
    List<String> lines = parseStackTrace(exception);
    int expectedFrameIndex =
        findFrameIndex(
            lines,
            "com.datadog.debugger.exception.DefaultExceptionDebuggerTest.createNestException");
    assertSnapshot(
        spanTags,
        snapshotMap,
        expectedFrameIndex,
        "com.datadog.debugger.exception.DefaultExceptionDebuggerTest",
        "createNestException");
    expectedFrameIndex =
        findFrameIndex(
            lines, "com.datadog.debugger.exception.DefaultExceptionDebuggerTest.nestedException");
    assertSnapshot(
        spanTags,
        snapshotMap,
        expectedFrameIndex,
        "com.datadog.debugger.exception.DefaultExceptionDebuggerTest",
        "nestedException");
    expectedFrameIndex =
        findFrameIndex(
            lines,
            "com.datadog.debugger.exception.DefaultExceptionDebuggerTest.createTest1Exception");
    assertSnapshot(
        spanTags,
        snapshotMap,
        expectedFrameIndex,
        "com.datadog.debugger.exception.DefaultExceptionDebuggerTest",
        "createTest1Exception");
    // make sure we are not leaking references
    exception = null; // release strong reference
    System.gc();
    // calling ExceptionProbeManager#hasExceptionStateTracked() will call WeakIdentityHashMap#size()
    // through isEmpty() an will purge stale entries
    assertWithTimeout(
        () -> !exceptionDebugger.getExceptionProbeManager().hasExceptionStateTracked(),
        Duration.ofSeconds(30));
  }

  @Test
  public void doubleNestedException() {
    RuntimeException nestedException = createNestException();
    String nestedFingerprint = Fingerprinter.fingerprint(nestedException, classNameFiltering);
    RuntimeException simpleException = new RuntimeException("test");
    String simpleFingerprint = Fingerprinter.fingerprint(simpleException, classNameFiltering);
    AgentSpan span = mock(AgentSpan.class);
    doAnswer(this::recordTags).when(span).setTag(anyString(), anyString());
    when(span.getTag(anyString()))
        .thenAnswer(invocationOnMock -> spanTags.get(invocationOnMock.getArgument(0)));
    when(span.getTags()).thenReturn(spanTags);
    // instrument first nested Exception
    exceptionDebugger.handleException(nestedException, span);
    // instrument first simple Exception
    exceptionDebugger.handleException(simpleException, span);
    assertWithTimeout(
        () -> exceptionDebugger.getExceptionProbeManager().isAlreadyInstrumented(nestedFingerprint),
        Duration.ofSeconds(30));
    assertWithTimeout(
        () -> exceptionDebugger.getExceptionProbeManager().isAlreadyInstrumented(simpleFingerprint),
        Duration.ofSeconds(30));
    generateSnapshots(nestedException);
    generateSnapshots(simpleException);
    exceptionDebugger.handleException(simpleException, span);
    nestedException.printStackTrace();
    exceptionDebugger.handleException(nestedException, span);
    ExceptionProbeManager.ThrowableState state =
        exceptionDebugger
            .getExceptionProbeManager()
            .getStateByThrowable(ExceptionHelper.getInnerMostThrowable(nestedException));
    assertEquals(
        state.getExceptionId(), spanTags.get(DefaultExceptionDebugger.DD_DEBUG_ERROR_EXCEPTION_ID));
    Map<String, Snapshot> snapshotMap =
        listener.snapshots.stream().collect(toMap(Snapshot::getId, Function.identity()));
    List<String> lines = parseStackTrace(nestedException);
    int expectedFrameIndex =
        findFrameIndex(
            lines,
            "com.datadog.debugger.exception.DefaultExceptionDebuggerTest.createNestException");
    assertSnapshot(
        spanTags,
        snapshotMap,
        expectedFrameIndex,
        "com.datadog.debugger.exception.DefaultExceptionDebuggerTest",
        "createNestException");
    expectedFrameIndex =
        findFrameIndex(
            lines,
            "com.datadog.debugger.exception.DefaultExceptionDebuggerTest.doubleNestedException");
    assertSnapshot(
        spanTags,
        snapshotMap,
        expectedFrameIndex,
        "com.datadog.debugger.exception.DefaultExceptionDebuggerTest",
        "doubleNestedException");
    expectedFrameIndex =
        findFrameIndex(
            lines,
            "com.datadog.debugger.exception.DefaultExceptionDebuggerTest.createTest1Exception");
    assertSnapshot(
        spanTags,
        snapshotMap,
        expectedFrameIndex,
        "com.datadog.debugger.exception.DefaultExceptionDebuggerTest",
        "createTest1Exception");
  }

  @Test
  public void innermostExceptionFullThirdParty() {
    RuntimeException exception = ThirdPartyCode.createThirdPartyException();
    AgentSpan span = mock(AgentSpan.class);
    doAnswer(this::recordTags).when(span).setTag(anyString(), anyString());
    exceptionDebugger.handleException(exception, span);
    // no probes created as all frames are filtered out (third-party code
    assertTrue(exceptionDebugger.getExceptionProbeManager().getProbes().isEmpty());
  }

  @Test
  public void nestedExceptionFullThirdParty() {
    RuntimeException thirdPartyException = ThirdPartyCode.createThirdPartyException();
    RuntimeException exception = createTest2Exception(thirdPartyException);
    String fingerprint = Fingerprinter.fingerprint(exception, classNameFiltering);
    AgentSpan span = mock(AgentSpan.class);
    doAnswer(this::recordTags).when(span).setTag(anyString(), anyString());
    exceptionDebugger.handleException(exception, span);
    assertWithTimeout(
        () -> exceptionDebugger.getExceptionProbeManager().isAlreadyInstrumented(fingerprint),
        Duration.ofSeconds(30));
    generateSnapshots(exception);
    exception.printStackTrace();
    exceptionDebugger.handleException(exception, span);
    ExceptionProbeManager.ThrowableState state =
        exceptionDebugger
            .getExceptionProbeManager()
            .getStateByThrowable(ExceptionHelper.getInnerMostThrowable(exception));
    assertEquals(
        state.getExceptionId(), spanTags.get(DefaultExceptionDebugger.DD_DEBUG_ERROR_EXCEPTION_ID));
    Map<String, Snapshot> snapshotMap =
        listener.snapshots.stream().collect(toMap(Snapshot::getId, Function.identity()));
    List<String> lines = parseStackTrace(exception);
    int expectedFrameIndex =
        findFrameIndex(
            lines,
            "com.datadog.debugger.exception.DefaultExceptionDebuggerTest.createTest2Exception");
    assertSnapshot(
        spanTags,
        snapshotMap,
        expectedFrameIndex,
        "com.datadog.debugger.exception.DefaultExceptionDebuggerTest",
        "createTest2Exception");
  }

  @Test
  public void filteringOutErrors() {
    ExceptionProbeManager manager = mock(ExceptionProbeManager.class);
    exceptionDebugger =
        new DefaultExceptionDebugger(manager, configurationUpdater, classNameFiltering, 100);
    exceptionDebugger.handleException(new AssertionError("test"), mock(AgentSpan.class));
    verify(manager, times(0)).isAlreadyInstrumented(any());
  }

  private Object recordTags(InvocationOnMock invocationOnMock) {
    Object[] args = invocationOnMock.getArguments();
    String key = (String) args[0];
    String value = (String) args[1];
    spanTags.put(key, value);
    return null;
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
      Map<String, Object> tags,
      Map<String, Snapshot> snapshotMap,
      int frameIndex,
      String className,
      String methodName) {
    String snapshotId = (String) tags.get(String.format(SNAPSHOT_ID_TAG_FMT, frameIndex));
    Snapshot snapshot = snapshotMap.get(snapshotId);
    assertEquals(className, snapshot.getProbe().getLocation().getType());
    assertEquals(methodName, snapshot.getProbe().getLocation().getMethod());
  }

  private void generateSnapshots(RuntimeException exception) {
    Map<String, ExceptionProbe> probesByLocation = buildProbesByLocation();
    Deque<Throwable> chainedExceptions = new ArrayDeque<>();
    ExceptionHelper.getInnerMostThrowable(exception, chainedExceptions);
    for (Throwable throwable : chainedExceptions) {
      int framesToRemove = -1;
      for (StackTraceElement element : throwable.getStackTrace()) {
        framesToRemove++;
        String probeLocation =
            element.getClassName() + "::" + element.getMethodName() + ":" + element.getLineNumber();
        ExceptionProbe exceptionProbe = probesByLocation.get(probeLocation);
        if (exceptionProbe == null) {
          continue;
        }
        evalAndCommitProbe(exception, exceptionProbe);
        rewriteSnapshotStacktrace(exception, throwable, framesToRemove);
      }
    }
  }

  private void rewriteSnapshotStacktrace(
      RuntimeException exception, Throwable throwable, int framesToRemove) {
    ExceptionProbeManager.ThrowableState state =
        exceptionDebugger
            .getExceptionProbeManager()
            .getStateByThrowable(ExceptionHelper.getInnerMostThrowable(exception));
    Snapshot lastSnapshot = state.getSnapshots().get(state.getSnapshots().size() - 1);
    lastSnapshot.getStack().clear();
    lastSnapshot
        .getStack()
        .addAll(
            Arrays.stream(throwable.getStackTrace())
                .skip(framesToRemove)
                .map(CapturedStackFrame::from)
                .collect(toList()));
  }

  private void evalAndCommitProbe(RuntimeException exception, ExceptionProbe exceptionProbe) {
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

  private Map<String, ExceptionProbe> buildProbesByLocation() {
    BinaryOperator<ExceptionProbe> dropMerger = (oldValue, newValue) -> oldValue;
    return exceptionDebugger.getExceptionProbeManager().getProbes().stream()
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

  public static Config createConfig() {
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

// Hacky way to generate an exception with a stacktrace that does not contain the test class
// we are starting a thread and create an exception in that thread and return it
class ThirdPartyCode extends Thread {
  volatile RuntimeException ex;

  public void run() {
    ex = new RuntimeException("third party code");
  }

  public static RuntimeException createThirdPartyException() {
    ThirdPartyCode thirdPartyCode = new ThirdPartyCode();
    thirdPartyCode.start();
    try {
      thirdPartyCode.join();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    return thirdPartyCode.ex;
  }
}
