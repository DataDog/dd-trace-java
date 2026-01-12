package com.datadog.debugger.exception;

import static com.datadog.debugger.exception.DefaultExceptionDebugger.SNAPSHOT_ID_TAG_FMT;
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
import static utils.TestHelper.assertWithTimeout;

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
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
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
  private TagMap spanTags = TagMap.create();

  @BeforeEach
  public void setUp() {
    configurationUpdater = mock(ConfigurationUpdater.class);
    classNameFiltering =
        new ClassNameFiltering(
            new HashSet<>(singletonList("com.datadog.debugger.exception.ThirdPartyCode")));
    Config config = createConfig();
    exceptionDebugger =
        new DefaultExceptionDebugger(configurationUpdater, classNameFiltering, config);
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
    exceptionDebugger.handleException(new AssertionError("test"), mock(AgentSpan.class));
    verify(manager, times(0)).isAlreadyInstrumented(any());
  }

  @Test
  public void lambdaTruncatedInnerTraceFallback() {
    AgentSpan span = mock(AgentSpan.class);
    doAnswer(this::recordTags).when(span).setTag(anyString(), anyString());
    when(span.getTag(anyString())).thenAnswer(inv -> spanTags.get(inv.getArgument(0)));
    when(span.getTags()).thenReturn(spanTags);

    // Create an exception with a real truncated stack trace from Lambda
    RuntimeException lambdaException =
        new RuntimeException("lambda") {
          @Override
          public StackTraceElement[] getStackTrace() {
            return new StackTraceElement[] {
              new StackTraceElement("Main", "handleRequest", "Main.java", 11),
              new StackTraceElement(
                  "jdk.internal.reflect.DirectMethodHandleAccessor",
                  "invoke",
                  "Unknown Source",
                  -1),
              new StackTraceElement("java.lang.reflect.Method", "invoke", "Unknown Source", -1)
            };
          }
        };

    // Set up the snapshot with a longer stack to represent original data
    List<CapturedStackFrame> snapshotStack = new ArrayList<>();
    snapshotStack.add(
        CapturedStackFrame.from(new StackTraceElement("Main", "handleRequest", "Main.java", 11)));
    for (int i = 0; i < 5; i++) {
      snapshotStack.add(
          CapturedStackFrame.from(
              new StackTraceElement("Lambda.Frame" + i, "method", "Lambda.java", 100 + i)));
    }

    // Mock snapshot
    Snapshot mockSnapshot = mock(Snapshot.class);
    when(mockSnapshot.getId()).thenReturn("test-snapshot-id");
    when(mockSnapshot.getStack()).thenReturn(snapshotStack);
    when(mockSnapshot.getChainedExceptionIdx()).thenReturn(0);

    // Mock probe
    ProbeLocation mockLocation = mock(ProbeLocation.class);
    when(mockLocation.getType()).thenReturn("Main");
    when(mockLocation.getMethod()).thenReturn("handleRequest");
    ProbeImplementation mockProbe = mock(ProbeImplementation.class);
    when(mockProbe.getLocation()).thenReturn(mockLocation);
    when(mockSnapshot.getProbe()).thenReturn(mockProbe);

    // Mock exception state
    ExceptionProbeManager.ThrowableState state = mock(ExceptionProbeManager.ThrowableState.class);
    when(state.getSnapshots()).thenReturn(singletonList(mockSnapshot));
    when(state.getExceptionId()).thenReturn("test-exception-id");
    when(state.isSnapshotSent()).thenReturn(false);

    // Create mock manager that returns our state
    ExceptionProbeManager mockManager = mock(ExceptionProbeManager.class);
    when(mockManager.isAlreadyInstrumented(anyString())).thenReturn(true);
    when(mockManager.getStateByThrowable(lambdaException)).thenReturn(state);

    DefaultExceptionDebugger testDebugger =
        new DefaultExceptionDebugger(mockManager, configurationUpdater, classNameFiltering, 100);

    // Test
    testDebugger.handleException(lambdaException, span);

    // Verify
    String tagName = String.format(SNAPSHOT_ID_TAG_FMT, 0);
    assertTrue(spanTags.containsKey(tagName));
    assertEquals("test-snapshot-id", spanTags.get(tagName));
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
        exceptionProbe, "", System.currentTimeMillis(), MethodLocation.EXIT, false);
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
    when(config.isDynamicInstrumentationEnabled()).thenReturn(true);
    when(config.isDynamicInstrumentationClassFileDumpEnabled()).thenReturn(true);
    when(config.isDynamicInstrumentationVerifyByteCode()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(100);
    when(config.getDebuggerExceptionCaptureInterval()).thenReturn(3600);
    when(config.getDebuggerMaxExceptionPerSecond()).thenReturn(100);
    when(config.getDebuggerExceptionMaxCapturedFrames()).thenReturn(3);
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
