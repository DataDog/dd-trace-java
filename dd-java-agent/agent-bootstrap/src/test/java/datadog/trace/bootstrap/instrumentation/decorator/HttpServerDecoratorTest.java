package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.context.Context.root;
import static datadog.trace.api.DDTags.HTTP_FRAGMENT;
import static datadog.trace.api.DDTags.HTTP_QUERY;
import static datadog.trace.api.config.AIGuardConfig.AI_GUARD_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_DECODED_RESOURCE_PRESERVE_SPACES;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.config.TracerConfig.CLIENT_IP_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_HEADER;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.api.gateway.RequestContextSlot.APPSEC;
import static datadog.trace.api.gateway.RequestContextSlot.IAST;
import static datadog.trace.bootstrap.instrumentation.api.ErrorPriorities.HTTP_SERVER_DECORATOR;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_404;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_PATH_NORMALIZER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_CLIENT_IP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_FORWARDED;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_FORWARDED_HOST;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_FORWARDED_IP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_FORWARDED_PORT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_FORWARDED_PROTO;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_HOSTNAME;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_USER_AGENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.NETWORK_CLIENT_IP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOST_IPV4;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOST_IPV6;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_PORT;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.core.datastreams.DataStreamsMonitoring;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class HttpServerDecoratorTest extends ServerDecoratorTest {

  static class MapCarrierVisitor implements AgentPropagation.ContextVisitor<Map<String, Object>> {
    @Override
    @SuppressWarnings("unchecked")
    public void forEachKey(Map<String, Object> carrier, AgentPropagation.KeyClassifier classifier) {
      Map<String, String> headers = (Map<String, String>) carrier.get("headers");
      if (headers != null) {
        headers.forEach(classifier::accept);
      }
    }
  }

  private boolean origAppSecActive;

  @BeforeEach
  void activateAppSec() {
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
    errorPriority = HTTP_SERVER_DECORATOR;
  }

  @AfterEach
  void restoreAppSec() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive;
  }

  @TableTest({
    "scenario           | withRequest | method      | url                          | path         | expectedUrl                ",
    "no request         | false       |             |                              |              |                            ",
    "query              | true        | test-method | 'http://test-url?some=query' | /            | 'http://test-url/'         ",
    "default http port  | true        | test-method | 'http://a:80/'               | /            | 'http://a/'                ",
    "default https port | true        | test-method | 'https://10.0.0.1:443'       | /            | 'https://10.0.0.1/'        ",
    "zero port          | true        | test-method | 'https://localhost:0/1/'     | '/?/'        | 'https://localhost/1/'     ",
    "numeric host       | true        | test-method | 'http://123:8080/some/path'  | '/some/path' | 'http://123:8080/some/path'"
  })
  void testOnRequest(
      boolean withRequest, String method, String url, String path, String expectedUrl) {
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    URI uri = url == null ? null : URI.create(url);
    Map<String, Object> req = withRequest ? request(method, uri) : null;
    when(span.getLocalRootSpan()).thenReturn(span);

    // when
    decorator.onRequest(span, null, req, root());

    // then
    if (req != null) {
      verify(span).setTag(HTTP_METHOD, "test-method");
      verify(span).setTag(eq(HTTP_QUERY), nullable(String.class));
      verify(span).setTag(eq(HTTP_FRAGMENT), nullable(String.class));
      verify(span).setTag(eq(HTTP_URL), hasText(expectedUrl));
      verify(span).setTag(HTTP_HOSTNAME, uri.getHost());
      verify(span, times(2)).getRequestContext();
      verify(span)
          .setResourceName(hasText(method.toUpperCase() + " " + path), eq(HTTP_PATH_NORMALIZER));
    } else {
      verify(span).getRequestContext();
    }
    verify(span, atLeast(0)).getLocalRootSpan();
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario                  | tagQueryString | url                                                    | expectedUrl           | expectedQuery             | expectedFragment       | expectedPath",
    "null url                  | false          |                                                        |                       |                           |                        | /           ",
    "empty url                 | false          | ''                                                     | /                     | ''                        |                        | /           ",
    "relative url with query   | false          | '/path?query'                                          | /path                 | ''                        |                        | /path       ",
    "zero port                 | false          | 'https://host:0'                                       | 'https://host/'       | ''                        |                        | /           ",
    "path                      | false          | 'https://host/path'                                    | 'https://host/path'   | ''                        |                        | /path       ",
    "port, query and fragment  | false          | 'http://host:99/path?query#fragment'                   | 'http://host:99/path' | ''                        |                        | /path       ",
    "tagged null url           | true           |                                                        |                       |                           |                        | /           ",
    "tagged empty url          | true           | ''                                                     | /                     |                           |                        | /           ",
    "tagged encoded query      | true           | '/path?encoded+%28query%29%3F?'                        | /path                 | 'encoded+%28query%29%3F?' |                        | /path       ",
    "tagged zero port          | true           | 'https://host:0'                                       | 'https://host/'       |                           |                        | /           ",
    "tagged path               | true           | 'https://host/path'                                    | 'https://host/path'   |                           |                        | /path       ",
    "tagged encoded fragment   | true           | 'http://host:99/path?query#enc+%28fragment%29%3F'      | 'http://host:99/path' | query                     | 'enc+(fragment)?'      | /path       ",
    "tagged fragment with tail | true           | 'http://host:99/path?query#enc+%28fragment%29%3F?tail' | 'http://host:99/path' | query                     | 'enc+(fragment)??tail' | /path       "
  })
  void testUrlHandling(
      boolean tagQueryString,
      String url,
      String expectedUrl,
      String expectedQuery,
      String expectedFragment,
      String expectedPath)
      throws URISyntaxException {
    injectSysConfig(HTTP_SERVER_TAG_QUERY_STRING, String.valueOf(tagQueryString));
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    URI uri = url == null ? null : new URI(url);
    Map<String, Object> req = singletonMap("url", uri);
    when(span.getLocalRootSpan()).thenReturn(span);

    // when
    decorator.onRequest(span, null, req, root());

    // then
    if (expectedUrl != null) {
      verify(span).setTag(eq(HTTP_URL), hasText(expectedUrl));
      verify(span, times(2)).getRequestContext();
    }
    if (expectedUrl != null && tagQueryString) {
      verify(span).setTag(HTTP_QUERY, expectedQuery);
      verify(span).setTag(HTTP_FRAGMENT, expectedFragment);
    }
    if (uri != null) {
      verify(span).setResourceName(hasText(expectedPath), eq(HTTP_PATH_NORMALIZER));
      if (uri.getHost() != null) {
        verify(span).setTag(HTTP_HOSTNAME, uri.getHost());
      }
    } else {
      verify(span).getRequestContext();
      verify(span).setResourceName(hasText(expectedPath));
    }
    verify(span).setTag(HTTP_METHOD, (String) null);
    verify(span, atLeast(0)).getLocalRootSpan();
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario                   | rawQuery | rawResource | url                                            | expectedUrl           | expectedQuery                 | expectedResource",
    "decoded                    | false    | false       | 'http://host/p%20ath?query%3F?'                | 'http://host/p ath'   | 'query??'                     | '/p ath'        ",
    "raw resource               | false    | true        | 'http://host/p%20ath?query%3F?'                | 'http://host/p%20ath' | 'query??'                     | '/p%20ath'      ",
    "raw query                  | true     | false       | 'http://host/p%20ath?query%3F?'                | 'http://host/p ath'   | 'query%3F?'                   | '/p ath'        ",
    "raw query and resource     | true     | true        | 'http://host/p%20ath?query%3F?'                | 'http://host/p%20ath' | 'query%3F?'                   | '/p%20ath'      ",
    "decoded encoded key        | false    | false       | 'http://host/path?t%6fken=first%26SECOND_HALF' | 'http://host/path'    | 'token=first%26SECOND_HALF'   | /path           ",
    "raw encoded key            | true     | false       | 'http://host/path?t%6fken=first%26SECOND_HALF' | 'http://host/path'    | 't%6fken=first%26SECOND_HALF' | /path           ",
    "decoded ampersand and hash | false    | false       | 'http://host/path?q=one%26two%23three'         | 'http://host/path'    | 'q=one%26two%23three'         | /path           "
  })
  @WithConfig(key = HTTP_SERVER_TAG_QUERY_STRING, value = "true")
  void testUrlHandlingWithRawQueryAndResource(
      boolean rawQuery,
      boolean rawResource,
      String url,
      String expectedUrl,
      String expectedQuery,
      String expectedResource)
      throws URISyntaxException {
    injectSysConfig(HTTP_SERVER_RAW_QUERY_STRING, String.valueOf(rawQuery));
    injectSysConfig(HTTP_SERVER_RAW_RESOURCE, String.valueOf(rawResource));
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    URI uri = new URI(url);
    when(span.getLocalRootSpan()).thenReturn(span);

    // when
    decorator.onRequest(span, null, singletonMap("url", uri), root());

    // then
    verify(span).setTag(eq(HTTP_URL), hasText(expectedUrl));
    verify(span).setTag(HTTP_HOSTNAME, uri.getHost());
    verify(span).setTag(HTTP_QUERY, expectedQuery);
    verify(span).setTag(HTTP_FRAGMENT, (String) null);
    verify(span, times(2)).getRequestContext();
    verify(span).setResourceName(hasText(expectedResource), eq(HTTP_PATH_NORMALIZER));
    verify(span).setTag(HTTP_METHOD, (String) null);
    verify(span, atLeast(0)).getLocalRootSpan();
    verifyNoMoreInteractions(span);
  }

  @Test
  @WithConfig(key = HTTP_SERVER_RAW_RESOURCE, value = "false")
  @WithConfig(key = HTTP_SERVER_DECODED_RESOURCE_PRESERVE_SPACES, value = "false")
  void urlHandlingWithoutSpacePreservation() {
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    when(span.getLocalRootSpan()).thenReturn(span);

    // when
    decorator.onRequest(span, null, singletonMap("url", URI.create("http://host/p%20ath")), root());

    // then
    verify(span).setResourceName(hasText("/path"), eq(HTTP_PATH_NORMALIZER));
  }

  @TableTest({
    "scenario       | withConnection | ipv4  | ip                                    | peerIp    | port",
    "no connection  | false          |       |                                       |           |     ",
    "peer ipv4      | true           | true  |                                       | 127.0.0.1 | 555 ",
    "forwarded ipv4 | true           | true  | 10.0.0.1                              |           | 555 ",
    "forwarded ipv6 | true           | false | '3ffe:1900:4545:3:200:f8ff:fe21:67cf' |           | 555 "
  })
  void testOnConnection(
      boolean withConnection, Boolean ipv4, String ip, String peerIp, Integer port) {
    AgentSpanContext.Extracted extracted = mock(AgentSpanContext.Extracted.class);
    Context context = AgentSpan.fromSpanContext(extracted);
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    Map<String, Object> conn = withConnection ? connection(ip, peerIp, port) : null;
    when(extracted.getForwarded())
        .thenReturn("by=<identifier>;for=<identifier>;host=<host>;proto=<http|https>");
    when(extracted.getForwardedFor()).thenReturn(null);
    when(extracted.getXForwardedProto()).thenReturn("https");
    when(extracted.getXForwardedHost()).thenReturn("somehost");
    when(extracted.getXForwardedFor()).thenReturn(ip);
    when(extracted.getXForwardedPort()).thenReturn("123");
    when(extracted.getXClusterClientIp()).thenReturn(null);
    when(extracted.getXRealIp()).thenReturn(null);
    when(extracted.getXClientIp()).thenReturn(null);
    when(extracted.getUserAgent()).thenReturn("some-user-agent");
    when(extracted.getCustomIpHeader()).thenReturn(null);
    when(extracted.getTrueClientIp()).thenReturn(null);
    when(extracted.getFastlyClientIp()).thenReturn(null);
    when(extracted.getCfConnectingIp()).thenReturn(null);
    when(extracted.getCfConnectingIpv6()).thenReturn(null);
    when(span.getRequestContext()).thenReturn(null);

    // when
    decorator.onRequest(span, conn, null, context);

    // then
    verify(span)
        .setTag(HTTP_FORWARDED, "by=<identifier>;for=<identifier>;host=<host>;proto=<http|https>");
    verify(span).setTag(HTTP_FORWARDED_PROTO, "https");
    verify(span).setTag(HTTP_FORWARDED_HOST, "somehost");
    if (peerIp != null) {
      verify(span).setTag(ipv4 ? PEER_HOST_IPV4 : PEER_HOST_IPV6, peerIp);
      verify(span).setTag(NETWORK_CLIENT_IP, peerIp);
    }
    if (ip != null) {
      verify(span).setTag(HTTP_CLIENT_IP, ip);
      verify(span).setTag(HTTP_FORWARDED_IP, ip);
    } else if (peerIp != null) {
      verify(span).setTag(HTTP_CLIENT_IP, peerIp);
    }
    verify(span).setTag(HTTP_FORWARDED_PORT, "123");
    if (port != null) {
      verify(span).setTag(PEER_PORT, (int) port);
    }
    verify(span).setTag(HTTP_USER_AGENT, "some-user-agent");
    verify(span, atLeast(0)).getRequestContext();
    verifyNoMoreInteractions(span);
    verifyNoMoreInteractions(ignoreStubs(extracted));
  }

  @TableTest({
    "scenario                   | headerIpAddr | peerIpAddr | result   ",
    "public header, public peer | 1.1.1.1      | 8.8.8.8    | 1.1.1.1  ",
    "public header, local peer  | 1.1.1.1      | 127.0.0.1  | 1.1.1.1  ",
    "local header, public peer  | 127.0.0.1    | 8.8.8.8    | 8.8.8.8  ",
    "no header, local peer      |              | 127.0.0.1  | 127.0.0.1"
  })
  void preferenceForHeaderDerivedVsPeerIpAddress(
      String headerIpAddr, String peerIpAddr, String result) {
    AgentSpanContext.Extracted extracted = mock(AgentSpanContext.Extracted.class);
    Context context = AgentSpan.fromSpanContext(extracted);
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    when(extracted.getXClientIp()).thenReturn(headerIpAddr);

    // when
    decorator.onRequest(span, singletonMap("peerIp", peerIpAddr), null, context);

    // then
    verify(extracted).getXClientIp();
    verify(span).setTag(HTTP_CLIENT_IP, result);
    verify(span).setTag(NETWORK_CLIENT_IP, peerIpAddr);
  }

  @Test
  @WithConfig(key = TRACE_CLIENT_IP_RESOLVER_ENABLED, value = "false")
  void disablingIpResolutionDisablesHeaderCollectionAndIpAddressResolution() {
    AgentSpanContext.Extracted extracted = mock(AgentSpanContext.Extracted.class);
    Context context = AgentSpan.fromSpanContext(extracted);
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();

    // when
    decorator.onRequest(span, singletonMap("peerIp", "4.4.4.4"), null, context);

    // then
    verify(extracted, never()).getForwarded();
    assertTagNotSet(span, HTTP_FORWARDED);
    assertTagNotSet(span, HTTP_CLIENT_IP);
    assertTagNotSet(span, NETWORK_CLIENT_IP);
  }

  @Test
  void disablingAppsecDisablesHeaderCollectionAndIpAddressResolution() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentSpanContext.Extracted extracted = mock(AgentSpanContext.Extracted.class);
    Context context = AgentSpan.fromSpanContext(extracted);
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();

    // when
    decorator.onRequest(span, singletonMap("peerIp", "4.4.4.4"), null, context);

    // then
    verify(extracted, never()).getForwarded();
    assertTagNotSet(span, HTTP_FORWARDED);
    assertTagNotSet(span, HTTP_CLIENT_IP);
    assertTagNotSet(span, NETWORK_CLIENT_IP);
  }

  @Test
  @WithConfig(key = AI_GUARD_ENABLED, value = "true")
  void enablingAiGuardCapturesClientIpDataWithoutTaggingTheSpanDuringRequestDecoration() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentSpanContext.Extracted extracted = mock(AgentSpanContext.Extracted.class);
    Context context = AgentSpan.fromSpanContext(extracted);
    RequestContext requestContext = mock(RequestContext.class);
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    when(extracted.getXForwardedFor()).thenReturn("2.3.4.5");
    when(span.getRequestContext()).thenReturn(requestContext);
    when(requestContext.getClientIpAddressData()).thenReturn(null);

    // when
    decorator.onRequest(span, singletonMap("peerIp", "4.4.4.4"), null, context);

    // then
    verify(requestContext)
        .setClientIpAddressData(
            argThat(data ->
                "4.4.4.4".equals(data.getPeerIp())
                    && "2.3.4.5".equals(data.getInferredClientIp())));
    assertTagNotSet(span, HTTP_CLIENT_IP);
    assertTagNotSet(span, NETWORK_CLIENT_IP);
    assertTagNotSet(span, HTTP_FORWARDED_IP);
  }

  @Test
  @WithConfig(key = AI_GUARD_ENABLED, value = "true")
  @WithConfig(key = CLIENT_IP_ENABLED, value = "true")
  void enablingAiGuardDoesNotOverrideClientIpWithoutAppsecTaggingBehavior() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentSpanContext.Extracted extracted = mock(AgentSpanContext.Extracted.class);
    Context context = AgentSpan.fromSpanContext(extracted);
    RequestContext requestContext = mock(RequestContext.class);
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    when(span.getRequestContext()).thenReturn(requestContext);
    when(extracted.getXForwardedFor()).thenReturn("2.3.4.5");

    // when
    decorator.onRequest(span, singletonMap("peerIp", "4.4.4.4"), null, context);

    // then
    verify(extracted, times(2)).getXForwardedFor();
    verify(span).setTag(HTTP_CLIENT_IP, "2.3.4.5");
    verify(span).setTag(NETWORK_CLIENT_IP, "4.4.4.4");
    // ai guard capture is skipped because tags were already set
    verify(requestContext, never()).setClientIpAddressData(any());
  }

  @Test
  @WithConfig(key = CLIENT_IP_ENABLED, value = "true")
  void
      disablingAppsecButEnablingClientIpWithoutAppsecEnablesHeaderCollectionAndIpAddressResolution() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentSpanContext.Extracted extracted = mock(AgentSpanContext.Extracted.class);
    Context context = AgentSpan.fromSpanContext(extracted);
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    when(span.getLocalRootSpan()).thenReturn(span);
    when(extracted.getXForwardedFor()).thenReturn("2.3.4.5");
    when(extracted.getForwarded()).thenReturn("for=9.9.9.9");

    // when
    decorator.onRequest(span, singletonMap("peerIp", "4.4.4.4"), null, context);

    // then
    verify(extracted, times(2)).getXForwardedFor();
    verify(span).setTag(HTTP_CLIENT_IP, "2.3.4.5");
    verify(span).setTag(HTTP_FORWARDED_IP, "2.3.4.5");
    verify(span).setTag(NETWORK_CLIENT_IP, "4.4.4.4");

    // Forwarded doesn't participate in client ip resolution anymore
    verify(extracted).getForwarded();
    verify(span).setTag(HTTP_FORWARDED, "for=9.9.9.9");
  }

  @Test
  @WithConfig(key = TRACE_CLIENT_IP_HEADER, value = "my-header")
  void clientIpReportingWithCustomHeader() {
    AgentSpanContext.Extracted extracted = mock(AgentSpanContext.Extracted.class);
    Context context = AgentSpan.fromSpanContext(extracted);
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    when(extracted.getCustomIpHeader()).thenReturn("5.5.5.5");

    // when
    decorator.onRequest(span, singletonMap("peerIp", "4.4.4.4"), null, context);

    // then
    verify(extracted).getCustomIpHeader();
    verify(span).setTag(HTTP_CLIENT_IP, "5.5.5.5");
    verify(span).setTag(NETWORK_CLIENT_IP, "4.4.4.4");
  }

  @TableTest({
    "scenario    | status | withResponse | error",
    "200         | 200    | true         | false",
    "399         | 399    | true         | false",
    "400         | 400    | true         | false",
    "404 first   | 404    | true         | false",
    "404 second  | 404    | true         | false",
    "499         | 499    | true         | false",
    "500         | 500    | true         | true ",
    "600         | 600    | true         | false",
    "no status   |        | true         | false",
    "no response |        | false        | false"
  })
  void testOnResponse(Integer status, boolean withResponse, boolean error) {
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator();
    Map<String, Object> resp = withResponse ? singletonMap("status", (Object) status) : null;

    // when
    decorator.onResponse(span, resp);

    // then
    if (status != null) {
      verify(span).setHttpStatusCode(status);
      verify(span).setError(error, HTTP_SERVER_DECORATOR);
    }
    if (status != null && status == 404) {
      verify(span).setResourceName(hasText("404"), eq(HTTP_404));
    }
    if (resp != null) {
      verify(span).getRequestContext();
    }
    verify(span, atLeast(0)).getTag("error.type");
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario        | headerTags                         | expectedTag                                                                                                      ",
    "no header tags  | [:]                                | [:]                                                                                                              ",
    "mapped header   | [x-custom-header: abc]             | [abc: custom-value]                                                                                              ",
    "wildcard prefix | ['*': 'datadog.response.headers.'] | [datadog.response.headers.x-custom-header: custom-value, datadog.response.headers.content-type: application/json]"
  })
  void testResponseHeadersWithTraceHeaderTags(
      Map<String, String> headerTags, Map<String, String> expectedTag) {
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.getResponseHeaderTags()).thenReturn(headerTags);
    Map<String, String> tags = new HashMap<>();
    AgentSpan responseSpan = mock(AgentSpan.class);
    when(responseSpan.traceConfig()).thenReturn(traceConfig);
    when(responseSpan.setTag(nullable(String.class), nullable(String.class)))
        .thenAnswer(invocation -> {
          tags.put(invocation.getArgument(0), invocation.getArgument(1));
          return responseSpan;
        });
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator(null, new MapCarrierVisitor());
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Custom-Header", "custom-value");
    headers.put("Content-Type", "application/json");
    Map<String, Object> resp = new HashMap<>();
    resp.put("status", 200);
    resp.put("headers", headers);

    // when
    decorator.onResponse(responseSpan, resp);

    // then
    assertEquals(expectedTag, tags);
  }

  @TableTest({
    "scenario                      | reqStarted | reqData | iastData | reqHeader | reqHeaderDone | reqStartedCount | reqHeaderCount | reqHeaderDoneCount",
    "no callbacks                  | false      | false   | false    | false     | false         | 0               | 0              | 0                 ",
    "data without callbacks        | false      | true    | false    | false     | false         | 0               | 0              | 0                 ",
    "request started               | true       | false   | false    | false     | false         | 1               | 0              | 0                 ",
    "request started with data     | true       | true    | false    | false     | false         | 1               | 0              | 0                 ",
    "request header                | true       | true    | false    | true      | false         | 1               | 3              | 0                 ",
    "request header done           | true       | true    | false    | true      | true          | 1               | 3              | 1                 ",
    "header callbacks without data | true       | false   | false    | true      | true          | 1               | 0              | 1                 ",
    "header callbacks, IAST data   | true       | false   | true     | true      | true          | 1               | 3              | 1                 "
  })
  void testStartSpanAndInstrumentationGateway(
      boolean reqStarted,
      boolean reqData,
      boolean iastData,
      boolean reqHeader,
      boolean reqHeaderDone,
      int reqStartedCount,
      int reqHeaderCount,
      int reqHeaderDoneCount) {
    InstrumentationGateway ig = new InstrumentationGateway();
    SubscriptionService ss = ig.getSubscriptionService(APPSEC);
    CallbackProvider cbpAppSec = ig.getCallbackProvider(APPSEC);
    Object data = reqData ? new Object() : null;
    IGCallBacks callbacks = new IGCallBacks(data);
    if (reqStarted) {
      ss.registerCallback(EVENTS.requestStarted(), callbacks);
    }
    if (reqHeader) {
      ss.registerCallback(EVENTS.requestHeader(), callbacks);
    }
    if (reqHeaderDone) {
      ss.registerCallback(EVENTS.requestHeaderDone(), callbacks);
    }
    Map<String, String> headers = new HashMap<>();
    headers.put("foo", "bar");
    headers.put("some", "thing");
    headers.put("another", "value");
    RequestContext reqCtxt = mock(RequestContext.class);
    when(reqCtxt.getData(APPSEC)).thenReturn(data);
    // Header callbacks only see headers when AppSec or IAST tracks the request.
    when(reqCtxt.getData(IAST)).thenReturn(iastData ? new Object() : null);
    AgentSpan mSpan = mock(AgentSpan.class);
    when(mSpan.getRequestContext()).thenReturn(reqCtxt);
    when(mSpan.setMeasured(true)).thenReturn(mSpan);
    TracerAPI mTracer = mock(TracerAPI.class);
    when(mTracer.startSpan(any(), any(), nullable(AgentSpanContext.class))).thenReturn(mSpan);
    when(mTracer.getCallbackProvider(APPSEC)).thenReturn(cbpAppSec);
    when(mTracer.getCallbackProvider(IAST))
        .thenReturn(CallbackProvider.CallbackProviderNoop.INSTANCE);
    // Universal callback lookups use the AppSec provider because no IAST callbacks are registered.
    when(mTracer.getUniversalCallbackProvider()).thenReturn(cbpAppSec);
    when(mTracer.getDataStreamsMonitoring()).thenReturn(mock(DataStreamsMonitoring.class));
    HttpServerDecorator<
            Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
        decorator = newDecorator(mTracer, null);

    // when
    decorator.startSpan(headers, root());

    // then
    verify(mSpan).setMeasured(true);
    assertEquals(reqStartedCount, callbacks.reqStartedCount);
    assertEquals(reqHeaderCount, callbacks.headers.size());
    assertEquals(reqHeaderDoneCount, callbacks.reqHeaderDoneCount);
    if (reqHeaderCount != 0) {
      assertEquals(headers, callbacks.headers);
    }
  }

  private static Map<String, Object> connection(String ip, String peerIp, Integer port) {
    Map<String, Object> connection = new HashMap<>();
    connection.put("ip", ip);
    connection.put("peerIp", peerIp);
    connection.put("port", port);
    return connection;
  }

  @Override
  protected HttpServerDecorator<
          Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
      newDecorator() {
    return newDecorator(null, null);
  }

  protected HttpServerDecorator<
          Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>
      newDecorator(
          TracerAPI tracer, AgentPropagation.ContextVisitor<Map<String, Object>> contextVisitor) {
    TracerAPI effectiveTracer = tracer != null ? tracer : AgentTracer.NOOP_TRACER;
    return new HttpServerDecorator<
        Map<String, Object>, Map<String, Object>, Map<String, Object>, Map<String, String>>() {
      @Override
      protected TracerAPI tracer() {
        return effectiveTracer;
      }

      @Override
      protected String[] instrumentationNames() {
        return new String[] {"test1", "test2"};
      }

      @Override
      protected CharSequence component() {
        return "test-component";
      }

      @Override
      protected AgentPropagation.ContextVisitor<Map<String, String>> getter() {
        return ContextVisitors.stringValuesMap();
      }

      @Override
      protected AgentPropagation.ContextVisitor<Map<String, Object>> responseGetter() {
        return contextVisitor;
      }

      @Override
      public CharSequence spanName() {
        return "http-test-span";
      }

      @Override
      protected String method(Map<String, Object> m) {
        return (String) m.get("method");
      }

      @Override
      protected URIDataAdapter url(Map<String, Object> m) {
        URI url = (URI) m.get("url");
        return url == null ? null : new URIDefaultDataAdapter(url);
      }

      @Override
      protected String peerHostIP(Map<String, Object> m) {
        return (String) m.get("peerIp");
      }

      @Override
      protected int peerPort(Map<String, Object> m) {
        Integer port = (Integer) m.get("port");
        return port == null ? 0 : port;
      }

      @Override
      protected int status(Map<String, Object> m) {
        Integer status = (Integer) m.get("status");
        return status == null ? 0 : status;
      }

      @Override
      protected String getRequestHeader(Map<String, Object> map, String key) {
        return (String) map.get(key);
      }
    };
  }

  private static final class IGCallBacks
      implements Supplier<Flow<Object>>,
          TriConsumer<RequestContext, String, String>,
          Function<RequestContext, Flow<Void>> {

    private final Object data;
    private final Map<String, String> headers = new HashMap<>();
    private int reqStartedCount = 0;
    private int reqHeaderDoneCount = 0;

    IGCallBacks(Object data) {
      this.data = data;
    }

    // REQUEST_STARTED
    @Override
    public Flow<Object> get() {
      reqStartedCount++;
      return null == data ? Flow.ResultFlow.empty() : new Flow.ResultFlow<>(data);
    }

    // REQUEST_HEADER
    @Override
    public void accept(RequestContext requestContext, String key, String value) {
      assertEquals(data, requestContext.getData(APPSEC));
      headers.put(key, value);
    }

    // REQUEST_HEADER_DONE
    @Override
    public Flow<Void> apply(RequestContext requestContext) {
      assertEquals(data, requestContext.getData(APPSEC));
      reqHeaderDoneCount++;
      return Flow.ResultFlow.empty();
    }
  }
}
