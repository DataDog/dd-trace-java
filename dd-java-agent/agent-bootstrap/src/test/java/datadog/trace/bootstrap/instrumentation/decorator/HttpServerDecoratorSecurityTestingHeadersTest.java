package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.context.Context.root;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.TraceConfig;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.core.datastreams.DataStreamsMonitoring;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

class HttpServerDecoratorSecurityTestingHeadersTest {

  private AgentSpan span;
  private Map<String, Object> tags;
  private HttpServerDecorator<Map<String, String>, ?, ?, Map<String, String>> decorator;

  @BeforeEach
  void setup() {
    tags = new HashMap<>();
    span = mock(AgentSpan.class);
    when(span.setTag(anyString(), anyString())).thenAnswer(this::recordTag);
    when(span.getRequestContext()).thenReturn(mock(RequestContext.class));
    when(span.setMeasured(true)).thenReturn(span);

    decorator = newDecorator(ContextVisitors.stringValuesMap());
  }

  private Object recordTag(InvocationOnMock invocation) {
    tags.put(invocation.getArgument(0), invocation.getArgument(1));
    return span;
  }

  @Test
  void tagsBothMarkersWhenPresentAndIgnoresUnrelatedHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-datadog-endpoint-scan", "scan-uuid-1");
    headers.put("x-datadog-security-test", "test-uuid-2");
    headers.put("x-other-header", "ignored");

    decorator.startSpan(headers, root());

    assertEquals("scan-uuid-1", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN));
    assertEquals("test-uuid-2", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST));
    verify(span, never()).setTag(eq("http.request.headers.x-other-header"), anyString());
  }

  @Test
  void doesNotTagWhenHeadersAreAbsent() {
    Map<String, String> headers = new HashMap<>();
    headers.put("content-type", "application/json");

    decorator.startSpan(headers, root());

    verify(span, never()).setTag(eq(HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN), anyString());
    verify(span, never()).setTag(eq(HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST), anyString());
  }

  @Test
  void matchesHeaderNamesCaseInsensitively() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Datadog-Endpoint-Scan", "scan-uuid-3");
    headers.put("X-DATADOG-SECURITY-TEST", "test-uuid-4");

    decorator.startSpan(headers, root());

    assertEquals("scan-uuid-3", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN));
    assertEquals("test-uuid-4", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST));
  }

  @Test
  void tagsHeadersEvenWhenValueIsEmptyString() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-datadog-endpoint-scan", "");
    headers.put("x-datadog-security-test", "ok");

    decorator.startSpan(headers, root());

    assertEquals("", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN));
    assertEquals("ok", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST));
  }

  @Test
  void tagsHeadersUnconditionallyEvenWhenTraceConfigHasOtherHeaderTags() {
    // RFC contract: tag regardless of DD_TRACE_HEADER_TAGS. Stub the trace config with an
    // unrelated header-tag mapping; the markers must still be tagged.
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(span.traceConfig()).thenReturn(traceConfig);
    when(traceConfig.getRequestHeaderTags())
        .thenReturn(Collections.singletonMap("x-other", "http.request.headers.x-other"));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-datadog-endpoint-scan", "scan-uuid");
    headers.put("x-datadog-security-test", "test-uuid");

    decorator.startSpan(headers, root());

    assertEquals("scan-uuid", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN));
    assertEquals("test-uuid", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST));
  }

  @Test
  void stopsIteratingAfterBothMarkersFound() {
    // Custom visitor that records which keys it offered to the classifier, so we can prove
    // the classifier short-circuits via `return false` once both markers are seen.
    List<String> visitedKeys = new ArrayList<>();
    AgentPropagation.ContextVisitor<Map<String, String>> trackingVisitor =
        (carrier, classifier) -> {
          for (Map.Entry<String, String> e : carrier.entrySet()) {
            visitedKeys.add(e.getKey());
            if (!classifier.accept(e.getKey(), e.getValue())) {
              return;
            }
          }
        };
    decorator = newDecorator(trackingVisitor);

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-datadog-endpoint-scan", "scan-uuid");
    headers.put("x-datadog-security-test", "test-uuid");
    headers.put("trailing-header-after-both-markers", "should-not-be-visited");

    decorator.startSpan(headers, root());

    assertEquals("scan-uuid", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN));
    assertEquals("test-uuid", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST));
    // Once both markers have been seen the classifier returns false, so iteration must stop
    // before the trailing header is offered.
    assertEquals(Arrays.asList("x-datadog-endpoint-scan", "x-datadog-security-test"), visitedKeys);
  }

  @Test
  void doesNotCrashOnNullHeaderValue() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-datadog-endpoint-scan", "scan-uuid");
    headers.put("x-datadog-security-test", "test-uuid");

    AgentPropagation.ContextVisitor<Map<String, String>> visitorWithNullValue =
        (carrier, classifier) -> {
          classifier.accept("x-datadog-endpoint-scan", null);
          for (Map.Entry<String, String> e : carrier.entrySet()) {
            if (!classifier.accept(e.getKey(), e.getValue())) {
              return;
            }
          }
        };
    decorator = newDecorator(visitorWithNullValue);

    decorator.startSpan(headers, root());

    // Null value is skipped; carrier still surfaces the marker with its real value next.
    assertEquals("scan-uuid", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN));
    assertEquals("test-uuid", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST));
  }

  @Test
  void doesNotCrashOnNullHeaderKey() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-datadog-endpoint-scan", "scan-uuid");
    headers.put("x-datadog-security-test", "test-uuid");

    AgentPropagation.ContextVisitor<Map<String, String>> visitorWithNullKey =
        (carrier, classifier) -> {
          classifier.accept(null, "should-be-ignored");
          for (Map.Entry<String, String> e : carrier.entrySet()) {
            if (!classifier.accept(e.getKey(), e.getValue())) {
              return;
            }
          }
        };
    decorator = newDecorator(visitorWithNullKey);

    decorator.startSpan(headers, root());

    assertEquals("scan-uuid", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_ENDPOINT_SCAN));
    assertEquals("test-uuid", tags.get(HTTP_REQUEST_HEADERS_X_DATADOG_SECURITY_TEST));
  }

  private HttpServerDecorator<Map<String, String>, ?, ?, Map<String, String>> newDecorator(
      AgentPropagation.ContextVisitor<Map<String, String>> visitor) {
    TracerAPI tracer = mock(TracerAPI.class);
    when(tracer.startSpan(any(), any(), any())).thenReturn(span);
    when(tracer.getDataStreamsMonitoring()).thenReturn(mock(DataStreamsMonitoring.class));
    when(tracer.getUniversalCallbackProvider())
        .thenReturn(AgentTracer.NOOP_TRACER.getUniversalCallbackProvider());
    when(tracer.getCallbackProvider(any()))
        .thenReturn(AgentTracer.NOOP_TRACER.getUniversalCallbackProvider());
    return new HttpServerDecorator<Map<String, String>, Object, Object, Map<String, String>>() {
      @Override
      protected TracerAPI tracer() {
        return tracer;
      }

      @Override
      protected String[] instrumentationNames() {
        return new String[] {"test"};
      }

      @Override
      protected CharSequence component() {
        return "test-component";
      }

      @Override
      public CharSequence spanName() {
        return "http-test-span";
      }

      @Override
      protected AgentPropagation.ContextVisitor<Map<String, String>> getter() {
        return visitor;
      }

      @Override
      protected AgentPropagation.ContextVisitor<Object> responseGetter() {
        return null;
      }

      @Override
      protected String method(Map<String, String> request) {
        return null;
      }

      @Override
      protected URIDataAdapter url(Map<String, String> request) {
        return null;
      }

      @Override
      protected String peerHostIP(Object connection) {
        return null;
      }

      @Override
      protected int peerPort(Object connection) {
        return 0;
      }

      @Override
      protected int status(Object response) {
        return 0;
      }
    };
  }
}
