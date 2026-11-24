package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.appsec.api.blocking.BlockingContentType.AUTO;
import static datadog.trace.api.DDTags.HTTP_FRAGMENT;
import static datadog.trace.api.DDTags.HTTP_QUERY;
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_RASP_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.api.gateway.RequestContextSlot.APPSEC;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_PATH_NORMALIZER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOSTNAME;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_PORT;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.appsec.HttpClientRequest;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class HttpClientDecoratorTest extends ClientDecoratorTest {

  private static final URI TEST_URL = URI.create("http://myhost:123/somepath");

  protected static final TracerAPI ORIGINAL_TRACER = AgentTracer.get();

  protected TraceSegment traceSegment;
  protected RequestContext reqCtx;
  protected AgentSpan span2;
  protected TracerAPI tracer;

  @BeforeEach
  void registerStubTracer() {
    traceSegment = mock(TraceSegment.class, RETURNS_MOCKS);
    reqCtx = mock(RequestContext.class, RETURNS_MOCKS);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    span2 = mock(AgentSpan.class, RETURNS_MOCKS);
    when(span2.getRequestContext()).thenReturn(reqCtx);
    tracer = mock(TracerAPI.class, RETURNS_MOCKS);
    when(tracer.activeSpan()).thenReturn(span2);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void restoreOriginalTracer() {
    AgentTracer.forceRegister(ORIGINAL_TRACER);
  }

  @TableTest({
    "scenario                    | renameService | withRequest | method      | url                          | path     ",
    "no request                  | false         | false       |             |                              |          ",
    "no request, split by domain | true          | false       |             |                              |          ",
    "request                     | false         | true        | test-method | 'http://myhost:123/somepath' | /somepath",
    "request, split by domain    | true          | true        | test-method | 'http://myhost:123/somepath' | /somepath"
  })
  void testOnRequest(
      boolean renameService, boolean withRequest, String method, String url, String path) {
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, String.valueOf(renameService));
    HttpClientDecorator<Map<String, Object>, Map<String, Object>> decorator = newDecorator();
    URI uri = url == null ? null : URI.create(url);
    Map<String, Object> req = withRequest ? request(method, uri) : null;
    TraceConfig traceConfig = AgentTracer.traceConfig();
    when(span.traceConfig()).thenReturn(traceConfig);

    // when
    decorator.onRequest(span, req);

    // then
    if (req != null) {
      verify(span).setTag(HTTP_METHOD, method);
      verify(span).setTag(eq(HTTP_URL), hasText(url));
      verify(span).setTag(HTTP_QUERY, (String) null);
      verify(span).setTag(HTTP_FRAGMENT, (String) null);
      verify(span).setTag(PEER_HOSTNAME, uri.getHost());
      verify(span).setTag(PEER_PORT, uri.getPort());
      verify(span)
          .setResourceName(hasText(method.toUpperCase() + " " + path), eq(HTTP_PATH_NORMALIZER));
      if (renameService) {
        verify(span).setServiceName(eq(uri.getHost()), any());
      }
      verify(span).traceConfig();
    }
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario                          | tagQueryString | url                                                   | expectedUrl           | expectedQuery               | expectedFragment      | hostname | port | expectedPath",
    "null url                          | false          |                                                       |                       |                             |                       |          |      | /           ",
    "empty url                         | false          | ''                                                    | /                     | ''                          |                       |          |      | /           ",
    "relative url with query           | false          | '/path?query'                                         | /path                 | ''                          |                       |          |      | /path       ",
    "zero port                         | false          | 'https://host:0'                                      | 'https://host/'       | ''                          |                       | host     |      | /           ",
    "path                              | false          | 'https://host/path'                                   | 'https://host/path'   | ''                          |                       | host     |      | /path       ",
    "port, query and fragment          | false          | 'http://host:99/path?query#fragment'                  | 'http://host:99/path' | ''                          |                       | host     | 99   | /path       ",
    "tagged null url                   | true           |                                                       |                       |                             |                       |          |      | /           ",
    "tagged empty url                  | true           | ''                                                    | /                     |                             |                       |          |      | /           ",
    "tagged encoded query              | true           | '/path?encoded+%28query%29%3F'                        | /path                 | 'encoded+(query)?'          |                       |          |      | /path       ",
    "tagged zero port                  | true           | 'https://host:0'                                      | 'https://host/'       |                             |                       | host     |      | /           ",
    "tagged path                       | true           | 'https://host/path'                                   | 'https://host/path'   |                             |                       | host     |      | /path       ",
    "tagged encoded fragment           | true           | 'http://host:99/path?query#encoded+%28fragment%29%3F' | 'http://host:99/path' | query                       | 'encoded+(fragment)?' | host     | 99   | /path       ",
    "tagged encoded ampersand          | true           | 'http://host/path?token=first%26SECOND_HALF'          | 'http://host/path'    | 'token=first%26SECOND_HALF' |                       | host     |      | /path       ",
    "tagged encoded key                | true           | 'http://host/path?t%6fken=first%26SECOND_HALF'        | 'http://host/path'    | 'token=first%26SECOND_HALF' |                       | host     |      | /path       ",
    "tagged encoded ampersand and hash | true           | 'http://host/path?q=one%26two%23three'                | 'http://host/path'    | 'q=one%26two%23three'       |                       | host     |      | /path       "
  })
  void testUrlHandling(
      boolean tagQueryString,
      String url,
      String expectedUrl,
      String expectedQuery,
      String expectedFragment,
      String hostname,
      Integer port,
      String expectedPath)
      throws URISyntaxException {
    injectSysConfig(HTTP_CLIENT_TAG_QUERY_STRING, String.valueOf(tagQueryString));
    HttpClientDecorator<Map<String, Object>, Map<String, Object>> decorator = newDecorator();
    Map<String, Object> req = singletonMap("url", url == null ? null : (Object) new URI(url));
    TraceConfig traceConfig = AgentTracer.traceConfig();
    when(span.traceConfig()).thenReturn(traceConfig);

    // when
    decorator.onRequest(span, req);

    // then
    if (expectedUrl != null) {
      verify(span).setTag(eq(HTTP_URL), hasText(expectedUrl));
    }
    if (expectedUrl != null && tagQueryString) {
      verify(span).setTag(HTTP_QUERY, expectedQuery);
      verify(span).setTag(HTTP_FRAGMENT, expectedFragment);
    }
    verify(span).setTag(HTTP_METHOD, (String) null);
    if (hostname != null) {
      verify(span).setTag(PEER_HOSTNAME, hostname);
    }
    if (port != null) {
      verify(span).setTag(PEER_PORT, (int) port);
    }
    if (url != null) {
      verify(span).setResourceName(hasText(expectedPath), eq(HTTP_PATH_NORMALIZER));
    } else {
      verify(span).setResourceName(hasText(expectedPath));
    }
    verify(span).traceConfig();
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario             | url                    | expectedServiceName",
    "null url             |                        |                    ",
    "empty url            | ''                     |                    ",
    "relative url         | '/path?query'          |                    ",
    "host                 | 'http://host:0'        | host               ",
    "host starting with a | 'http://ahost:0'       | ahost              ",
    "upper case host      | 'http://AHOST:0'       | AHOST              ",
    "host with digits     | 'https://host123/path' | host123            ",
    "host starting digit  | 'https://123host/path' |                    ",
    "ip address           | 'http://10.20.30.40'   |                    "
  })
  @WithConfig(key = HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, value = "true")
  void testSplitByDomain(String url, String expectedServiceName) throws URISyntaxException {
    HttpClientDecorator<Map<String, Object>, Map<String, Object>> decorator = newDecorator();
    Map<String, Object> req = singletonMap("url", url == null ? null : (Object) new URI(url));
    TraceConfig traceConfig = AgentTracer.traceConfig();
    when(span.traceConfig()).thenReturn(traceConfig);

    // when
    decorator.onRequest(span, req);

    // then
    if (expectedServiceName != null) {
      verify(span).setServiceName(eq(expectedServiceName), any());
    }
    if (url != null) {
      verify(span).setResourceName(any(), anyByte());
    } else {
      verify(span).setResourceName(any());
    }
    verify(span).traceConfig();
    ignoreInteractions(span, "setTag", 2);
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario    | status | withResponse | error",
    "200         | 200    | true         | false",
    "399         | 399    | true         | false",
    "400         | 400    | true         | true ",
    "499         | 499    | true         | true ",
    "500 first   | 500    | true         | false",
    "500 second  | 500    | true         | false",
    "500 third   | 500    | true         | false",
    "600         | 600    | true         | false",
    "no status   |        | true         | false",
    "no response |        | false        | false"
  })
  void testOnResponse(Integer status, boolean withResponse, boolean error) {
    HttpClientDecorator<Map<String, Object>, Map<String, Object>> decorator = newDecorator();
    Map<String, Object> resp = withResponse ? singletonMap("status", (Object) status) : null;
    TraceConfig traceConfig = AgentTracer.traceConfig();
    when(span.traceConfig()).thenReturn(traceConfig);

    // when
    decorator.onResponse(span, resp);

    // then
    if (status != null) {
      verify(span).setHttpStatusCode(status);
    }
    if (error) {
      verify(span).setError(true);
    }
    if (resp != null) {
      verify(span).traceConfig();
    }
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario      | iastEnabled",
    "iast enabled  | true       ",
    "iast disabled | false      "
  })
  void testSsrfIastCheckIsCalled(String iastEnabled) {
    injectSysConfig(IAST_ENABLED, iastEnabled);
    HttpClientDecorator<Map<String, Object>, Map<String, Object>> decorator = newDecorator();
    SsrfModule module = mock(SsrfModule.class);
    InstrumentationBridge.registerIastModule(module);
    Map<String, Object> req = request("test-method", TEST_URL);
    TraceConfig traceConfig = AgentTracer.traceConfig();
    when(span.traceConfig()).thenReturn(traceConfig);

    // when
    decorator.onRequest(span, req);

    // then
    if ("true".equals(iastEnabled)) {
      verify(module).onURLConnection(any());
    } else {
      verify(module, never()).onURLConnection(any());
    }
    verify(span).traceConfig();
  }

  @Test
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  @WithConfig(key = APPSEC_RASP_ENABLED, value = "true")
  @SuppressWarnings("unchecked")
  void testSsrfExploitPreventionOnResponse() {
    CallbackProvider callbackProvider = mock(CallbackProvider.class);
    BiFunction<RequestContext, HttpClientRequest, Flow<Void>> listener = mock(BiFunction.class);
    when(tracer.getCallbackProvider(APPSEC)).thenReturn(callbackProvider);
    when(callbackProvider.getCallback(EVENTS.httpClientRequest())).thenReturn(listener);
    HttpClientDecorator<Map<String, Object>, Map<String, Object>> decorator = newDecorator();
    Map<String, Object> req = request("GET", URI.create("http://localhost:1234/somepath"));

    // when
    decorator.onRequest(span2, req);

    // then
    verify(callbackProvider).getCallback(EVENTS.httpClientRequest());
    verify(listener).apply(eq(reqCtx), any(HttpClientRequest.class));
  }

  /**
   * Fail-closed invariant: RASP must abort the SSRF attempt even when no blocking response can be
   * committed, so the throw happens whether or not a {@link BlockResponseFunction} is present.
   */
  @Test
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  @WithConfig(key = APPSEC_RASP_ENABLED, value = "true")
  void testSsrfBlockingThrowsEvenWithoutABlockResponseFunction() {
    RequestBlockingAction rba = new RequestBlockingAction(403, AUTO);
    stubHttpClientRequestCallback(rba);
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);
    HttpClientDecorator<Map<String, Object>, Map<String, Object>> decorator = newDecorator();
    Map<String, Object> req = request("GET", URI.create("http://localhost:1234/somepath"));

    // when / then
    assertThrows(BlockingException.class, () -> decorator.onRequest(span2, req));
  }

  @Test
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  @WithConfig(key = APPSEC_RASP_ENABLED, value = "true")
  void testSsrfBlockingCommitsAndThrowsWhenABlockResponseFunctionIsPresent() {
    RequestBlockingAction rba = new RequestBlockingAction(403, AUTO);
    BlockResponseFunction brf = mock(BlockResponseFunction.class);
    stubHttpClientRequestCallback(rba);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);
    HttpClientDecorator<Map<String, Object>, Map<String, Object>> decorator = newDecorator();
    Map<String, Object> req = request("GET", URI.create("http://localhost:1234/somepath"));

    // when / then
    assertThrows(BlockingException.class, () -> decorator.onRequest(span2, req));
    verify(brf).tryCommitBlockingResponse(reqCtx, rba);
  }

  @SuppressWarnings("unchecked")
  private void stubHttpClientRequestCallback(RequestBlockingAction rba) {
    CallbackProvider callbackProvider = mock(CallbackProvider.class);
    BiFunction<RequestContext, HttpClientRequest, Flow<Void>> listener = mock(BiFunction.class);
    when(tracer.getCallbackProvider(APPSEC)).thenReturn(callbackProvider);
    when(callbackProvider.getCallback(EVENTS.httpClientRequest())).thenReturn(listener);
    when(listener.apply(eq(reqCtx), any(HttpClientRequest.class))).thenReturn(blockingFlow(rba));
  }

  @Override
  protected HttpClientDecorator<Map<String, Object>, Map<String, Object>> newDecorator() {
    return newDecorator("test-service");
  }

  @Override
  protected HttpClientDecorator<Map<String, Object>, Map<String, Object>> newDecorator(
      String serviceName) {
    return new HttpClientDecorator<Map<String, Object>, Map<String, Object>>() {
      @Override
      protected String[] instrumentationNames() {
        return new String[] {"test1", "test2"};
      }

      @Override
      protected String service() {
        return serviceName;
      }

      @Override
      protected CharSequence component() {
        return "test-component";
      }

      @Override
      protected String method(Map<String, Object> m) {
        return (String) m.get("method");
      }

      @Override
      protected URI url(Map<String, Object> m) {
        return (URI) m.get("url");
      }

      @Override
      protected String sourceUrl(Map<String, Object> m) {
        Object url = m.get("url");
        return url == null ? null : url.toString();
      }

      @Override
      protected int status(Map<String, Object> m) {
        Integer status = (Integer) m.get("status");
        return status == null ? 0 : status;
      }

      @Override
      protected boolean traceAnalyticsDefault() {
        return true;
      }

      @Override
      protected String getRequestHeader(Map<String, Object> map, String headerName) {
        return null;
      }

      @Override
      protected String getResponseHeader(Map<String, Object> map, String headerName) {
        return null;
      }
    };
  }
}
