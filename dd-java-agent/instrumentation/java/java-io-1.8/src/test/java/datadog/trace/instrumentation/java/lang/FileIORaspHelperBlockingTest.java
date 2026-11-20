package datadog.trace.instrumentation.java.lang;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.File;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the fail-closed blocking behavior of the LFI RASP check: when the AppSec callback returns a
 * real {@link Flow.Action.RequestBlockingAction}, a {@link BlockingException} is always thrown from
 * every public entry point, whether or not a {@link BlockResponseFunction} is present and
 * regardless of whether it managed to commit the blocking response.
 */
class FileIORaspHelperBlockingTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.JSON);

  private AgentTracer.TracerAPI originalTracer;
  private FileIORaspHelper originalHelperInstance;
  private TraceSegment traceSegment;
  private RequestContext reqCtx;

  /**
   * Several pre-existing Groovy specs in this module replace {@link FileIORaspHelper#INSTANCE} with
   * a mock and never restore it, leaking a permanently-neutered singleton into every test that runs
   * afterwards in the same test JVM. Force a genuine instance for the duration of this test
   * regardless of execution order, and restore whatever was there before.
   */
  private static FileIORaspHelper newRealInstance() throws ReflectiveOperationException {
    Constructor<FileIORaspHelper> ctor = FileIORaspHelper.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    return ctor.newInstance();
  }

  @BeforeEach
  void setUp() throws ReflectiveOperationException {
    originalHelperInstance = FileIORaspHelper.INSTANCE;
    FileIORaspHelper.INSTANCE = newRealInstance();

    originalTracer = AgentTracer.get();
    traceSegment = mock(TraceSegment.class);
    reqCtx = mock(RequestContext.class);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);

    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(reqCtx);

    @SuppressWarnings("unchecked")
    Flow<Void> blockingFlow = mock(Flow.class);
    when(blockingFlow.getAction()).thenReturn(RBA);
    CallbackProvider callbackProvider = mock(CallbackProvider.class);
    when(callbackProvider.getCallback(EVENTS.fileLoaded())).thenReturn((ctx, path) -> blockingFlow);
    when(callbackProvider.getCallback(EVENTS.fileWritten()))
        .thenReturn((ctx, path) -> blockingFlow);

    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.activeSpan()).thenReturn(span);
    when(tracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(callbackProvider);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
    FileIORaspHelper.INSTANCE = originalHelperInstance;
  }

  static Stream<Arguments> entryPoints() {
    // Read INSTANCE lazily inside each Executable, not here: @MethodSource is resolved by JUnit
    // before @BeforeEach runs, so capturing INSTANCE at this point could still observe a mock
    // leaked by an earlier Groovy spec in this module (see setUp()'s javadoc).
    return Stream.of(
        arguments(
            "beforeFileLoaded(String)",
            (Executable) () -> FileIORaspHelper.INSTANCE.beforeFileLoaded("f")),
        arguments(
            "beforeFileLoaded(String, String)",
            (Executable) () -> FileIORaspHelper.INSTANCE.beforeFileLoaded("/tmp", "f")),
        arguments(
            "beforeFileLoaded(String, String[])",
            (Executable)
                () ->
                    FileIORaspHelper.INSTANCE.beforeFileLoaded("/tmp", new String[] {"log", "f"})),
        arguments(
            "beforeFileLoaded(File, String)",
            (Executable) () -> FileIORaspHelper.INSTANCE.beforeFileLoaded(new File("/tmp"), "f")),
        arguments(
            "beforeFileLoaded(URI)",
            (Executable)
                () -> FileIORaspHelper.INSTANCE.beforeFileLoaded(URI.create("file:/tmp/f"))),
        arguments(
            "beforeFileWritten(String)",
            (Executable) () -> FileIORaspHelper.INSTANCE.beforeFileWritten("f")),
        arguments(
            "beforeRandomAccessFileOpened(String, String)",
            (Executable) () -> FileIORaspHelper.INSTANCE.beforeRandomAccessFileOpened("f", "rw")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("entryPoints")
  void throwsWithoutBlockResponseFunction(String name, Executable entryPoint) {
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    assertThrows(BlockingException.class, entryPoint);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("entryPoints")
  void throwsWhenBlockResponseFunctionCommits(String name, Executable entryPoint) {
    assertThrowsAndCommits(entryPoint, true);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("entryPoints")
  void throwsWhenBlockResponseFunctionFailsToCommit(String name, Executable entryPoint) {
    assertThrowsAndCommits(entryPoint, false);
  }

  private void assertThrowsAndCommits(Executable entryPoint, boolean commitResult) {
    RecordingBlockResponseFunction brf = new RecordingBlockResponseFunction(commitResult);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);

    assertThrows(BlockingException.class, entryPoint);

    brf.assertCommittedOnce(traceSegment);
  }

  /**
   * Hand-written fake that only implements the abstract 5-arg method, so it records the commit
   * whichever {@code tryCommitBlockingResponse} overload the production code calls.
   */
  private static final class RecordingBlockResponseFunction implements BlockResponseFunction {
    private final boolean commitResult;
    private int calls;
    private TraceSegment lastSegment;
    private int lastStatusCode;
    private BlockingContentType lastTemplateType;

    private RecordingBlockResponseFunction(boolean commitResult) {
      this.commitResult = commitResult;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      calls++;
      lastSegment = segment;
      lastStatusCode = statusCode;
      lastTemplateType = templateType;
      return commitResult;
    }

    private void assertCommittedOnce(TraceSegment expectedSegment) {
      // the first blocking event aborts the call, so a commit is attempted exactly once
      assertEquals(1, calls);
      assertSame(expectedSegment, lastSegment);
      assertEquals(RBA.getStatusCode(), lastStatusCode);
      assertEquals(RBA.getBlockingContentType(), lastTemplateType);
    }
  }
}
