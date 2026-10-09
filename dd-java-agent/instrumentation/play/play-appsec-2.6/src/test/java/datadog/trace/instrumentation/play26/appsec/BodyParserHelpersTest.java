package datadog.trace.instrumentation.play26.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.api.libs.json.JsValue;
import play.api.mvc.MultipartFormData;
import scala.collection.JavaConverters;
import scala.xml.NodeSeq;
import scala.xml.XML$;

class BodyParserHelpersTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  @AfterEach
  void restoreTracer() {
    AgentTracer.forceRegister(originalTracer);
  }

  private static JsValue parse(String json) {
    return play.api.libs.json.Json$.MODULE$.parse(json);
  }

  @Test
  void jsValueToJavaObject_nullInputReturnsNull() {
    assertNull(BodyParserHelpers.jsValueToJavaObject(null));
  }

  @Test
  void jsValueToJavaObject_jsNullReturnsNull() {
    assertNull(BodyParserHelpers.jsValueToJavaObject(parse("null")));
  }

  @Test
  void jsValueToJavaObject_string() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("\"hello\""));
    assertEquals("hello", result);
  }

  @Test
  void jsValueToJavaObject_number() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("42"));
    assertTrue(result instanceof BigDecimal);
    assertEquals(0, ((BigDecimal) result).compareTo(new BigDecimal("42")));
  }

  @Test
  void jsValueToJavaObject_booleanTrue() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("true"));
    assertEquals(Boolean.TRUE, result);
  }

  @Test
  void jsValueToJavaObject_booleanFalse() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("false"));
    assertEquals(Boolean.FALSE, result);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsValueToJavaObject_object() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("{\"key\":\"value\",\"num\":1}"));
    assertTrue(result instanceof Map);
    Map<String, Object> map = (Map<String, Object>) result;
    assertEquals("value", map.get("key"));
    assertTrue(map.get("num") instanceof BigDecimal);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsValueToJavaObject_array() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("[\"a\",\"b\",\"c\"]"));
    assertTrue(result instanceof List);
    List<Object> list = (List<Object>) result;
    assertEquals(3, list.size());
    assertEquals("a", list.get(0));
    assertEquals("b", list.get(1));
    assertEquals("c", list.get(2));
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsValueToJavaObject_nestedObject() {
    Object result =
        BodyParserHelpers.jsValueToJavaObject(parse("{\"outer\":{\"inner\":\"deep\"}}"));
    assertTrue(result instanceof Map);
    Map<String, Object> outer = (Map<String, Object>) result;
    assertTrue(outer.get("outer") instanceof Map);
    Map<String, Object> inner = (Map<String, Object>) outer.get("outer");
    assertEquals("deep", inner.get("inner"));
  }

  @Test
  void jsValueToJavaObject_zeroRecursionReturnsNull() {
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("{\"key\":\"value\"}"), 0);
    assertNull(result);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsValueToJavaObject_recursionLimitTruncatesNesting() {
    // depth=1 means the object itself is converted but children are null
    Object result = BodyParserHelpers.jsValueToJavaObject(parse("{\"a\":{\"b\":\"val\"}}"), 1);
    assertTrue(result instanceof Map);
    Map<String, Object> map = (Map<String, Object>) result;
    // inner object exceeds depth so its value is null
    assertNull(map.get("a"));
  }

  // --- collectFilenames tests ---

  @Test
  void collectFilenames_emptyIterator() {
    List<String> result = BodyParserHelpers.collectFilenames(Collections.emptyIterator());
    assertTrue(result.isEmpty());
  }

  @Test
  void collectFilenames_nullFilenameExcluded() throws Exception {
    List<String> result =
        BodyParserHelpers.collectFilenames(
            Collections.<Object>singletonList(filePart("f", null)).iterator());
    assertTrue(result.isEmpty());
  }

  @Test
  void collectFilenames_emptyFilenameExcluded() throws Exception {
    List<String> result =
        BodyParserHelpers.collectFilenames(
            Collections.<Object>singletonList(filePart("f", "")).iterator());
    assertTrue(result.isEmpty());
  }

  @Test
  void collectFilenames_validFilenameIncluded() throws Exception {
    List<String> result =
        BodyParserHelpers.collectFilenames(
            Collections.<Object>singletonList(filePart("f", "evil.php")).iterator());
    assertEquals(Collections.singletonList("evil.php"), result);
  }

  @Test
  void collectFilenames_mixedPartsFiltered() throws Exception {
    List<Object> parts =
        Arrays.<Object>asList(
            filePart("f1", "a.pdf"),
            filePart("f2", null),
            filePart("f3", ""),
            filePart("f4", "b.jpg"));
    List<String> result = BodyParserHelpers.collectFilenames(parts.iterator());
    assertEquals(Arrays.asList("a.pdf", "b.jpg"), result);
  }

  // --- blocking tests: pin that a RequestBlockingAction returned by the AppSec callback is
  // honored (commit attempted, BlockingException thrown only if the commit succeeds) ---

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void multipartFilenamesCallbackBlocks(boolean commitResult) throws Exception {
    AppSecFixture fx = new AppSecFixture(commitResult);
    fx.onFilenames((ctx, names) -> blockingFlow());
    fx.onFilesContent((ctx, contents) -> fx.contentCallbackInvoked());
    fx.register();
    MultipartFormData<Object> data = multipartFormData(filePart("f", "evil.php"));

    if (commitResult) {
      BlockingException ex =
          assertThrows(
              BlockingException.class,
              () -> BodyParserHelpers.getHandleMultipartFormDataF().apply(data));
      assertEquals("Blocked request (multipart file upload)", ex.getMessage());
      // files content inspection is skipped once a block is pending
      assertEquals(0, fx.contentCallbackInvocations);
    } else {
      assertSame(data, BodyParserHelpers.getHandleMultipartFormDataF().apply(data));
      assertEquals(1, fx.contentCallbackInvocations);
    }
    fx.brf.assertCommittedOnce(fx.segment);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void multipartFilesContentCallbackBlocks(boolean commitResult) throws Exception {
    AppSecFixture fx = new AppSecFixture(commitResult);
    fx.onFilenames((ctx, names) -> Flow.ResultFlow.empty());
    fx.onFilesContent((ctx, contents) -> blockingFlow());
    fx.register();
    MultipartFormData<Object> data = multipartFormData(filePart("f", "evil.php"));

    if (commitResult) {
      BlockingException ex =
          assertThrows(
              BlockingException.class,
              () -> BodyParserHelpers.getHandleMultipartFormDataF().apply(data));
      assertEquals("Blocked request (multipart file upload content)", ex.getMessage());
    } else {
      assertSame(data, BodyParserHelpers.getHandleMultipartFormDataF().apply(data));
    }
    fx.brf.assertCommittedOnce(fx.segment);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void xmlBodyCallbackBlocks(boolean commitResult) {
    AppSecFixture fx = new AppSecFixture(commitResult);
    fx.onRequestBody((ctx, body) -> blockingFlow());
    fx.register();
    NodeSeq xml = XML$.MODULE$.loadString("<root attr=\"v\">text</root>");

    if (commitResult) {
      BlockingException ex =
          assertThrows(BlockingException.class, () -> BodyParserHelpers.getHandleXmlF().apply(xml));
      assertEquals("Blocked request (for xml)", ex.getMessage());
    } else {
      assertSame(xml, BodyParserHelpers.getHandleXmlF().apply(xml));
    }
    fx.brf.assertCommittedOnce(fx.segment);
  }

  private static Flow<Void> blockingFlow() {
    return new Flow<Void>() {
      @Override
      public Action getAction() {
        return RBA;
      }

      @Override
      public Void getResult() {
        return null;
      }
    };
  }

  @SafeVarargs
  @SuppressWarnings("unchecked")
  private static MultipartFormData<Object> multipartFormData(
      MultipartFormData.FilePart<Object>... files) {
    return new MultipartFormData<>(
        (scala.collection.immutable.Map<String, scala.collection.Seq<String>>)
            (Object) scala.collection.immutable.Map$.MODULE$.empty(),
        JavaConverters.asScalaBufferConverter(Arrays.asList(files)).asScala(),
        JavaConverters.asScalaBufferConverter(Collections.<MultipartFormData.BadPart>emptyList())
            .asScala());
  }

  /** Wires an active span with AppSec request data and stubs the AppSec gateway callbacks. */
  private static final class AppSecFixture {
    final TraceSegment segment = mock(TraceSegment.class);
    final RecordingBlockResponseFunction brf;
    final RequestContext reqCtx = mock(RequestContext.class);
    final CallbackProvider cbp = mock(CallbackProvider.class);
    int contentCallbackInvocations;

    AppSecFixture(boolean commitResult) {
      brf = new RecordingBlockResponseFunction(commitResult);
      when(reqCtx.getTraceSegment()).thenReturn(segment);
      when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(new Object());
      when(reqCtx.getBlockResponseFunction()).thenReturn(brf);
    }

    void onFilenames(BiFunction<RequestContext, List<String>, Flow<Void>> cb) {
      when(cbp.getCallback(EVENTS.requestFilesFilenames())).thenReturn(cb);
    }

    void onFilesContent(BiFunction<RequestContext, List<String>, Flow<Void>> cb) {
      when(cbp.getCallback(EVENTS.requestFilesContent())).thenReturn(cb);
    }

    void onRequestBody(BiFunction<RequestContext, Object, Flow<Void>> cb) {
      when(cbp.getCallback(EVENTS.requestBodyProcessed())).thenReturn(cb);
    }

    Flow<Void> contentCallbackInvoked() {
      contentCallbackInvocations++;
      return Flow.ResultFlow.empty();
    }

    void register() {
      AgentSpan span = mock(AgentSpan.class);
      when(span.getRequestContext()).thenReturn(reqCtx);
      AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
      when(tracer.getCallbackProvider(any(RequestContextSlot.class))).thenReturn(cbp);
      when(tracer.activeSpan()).thenReturn(span);
      AgentTracer.forceRegister(tracer);
    }
  }

  /** Fake implementing only the abstract 5-arg method, so any default overload routes here. */
  private static final class RecordingBlockResponseFunction implements BlockResponseFunction {
    private final boolean commitResult;
    private int invocations;
    private TraceSegment segment;
    private int statusCode;
    private BlockingContentType templateType;

    RecordingBlockResponseFunction(boolean commitResult) {
      this.commitResult = commitResult;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      invocations++;
      this.segment = segment;
      this.statusCode = statusCode;
      this.templateType = templateType;
      return commitResult;
    }

    void assertCommittedOnce(TraceSegment expectedSegment) {
      assertEquals(1, invocations);
      assertSame(expectedSegment, segment);
      assertEquals(403, statusCode);
      assertEquals(BlockingContentType.AUTO, templateType);
    }
  }

  @SuppressWarnings("unchecked")
  private static MultipartFormData.FilePart<Object> filePart(String key, String filename)
      throws Exception {
    // FilePart is a Scala case class nested in object MultipartFormData.
    // Use the companion object's apply() to avoid JVM inner-class constructor complexity.
    Class<?> companionClass = Class.forName("play.api.mvc.MultipartFormData$FilePart$");
    Object companion = companionClass.getField("MODULE$").get(null);
    for (Method m : companionClass.getMethods()) {
      if ("apply".equals(m.getName()) && m.getParameterCount() == 4) {
        return (MultipartFormData.FilePart<Object>)
            m.invoke(companion, key, filename, scala.None$.MODULE$, new Object());
      }
    }
    throw new IllegalStateException("FilePart.apply(4 params) not found");
  }
}
