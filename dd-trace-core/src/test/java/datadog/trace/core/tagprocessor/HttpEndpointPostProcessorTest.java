package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import datadog.trace.api.TagMap;
import datadog.trace.api.endpoint.EndpointResolver;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HttpEndpointPostProcessorTest {

  @Mock DDSpanContext mockContext;

  @Test
  void shouldNotOverwriteResourceNameWhenHttpRouteIsAvailableAndEligible() {
    EndpointResolver endpointResolver = new EndpointResolver(true, false);
    HttpEndpointPostProcessor processor = new HttpEndpointPostProcessor(endpointResolver);
    TagMap tags =
        TagMap.fromMap(
            new java.util.LinkedHashMap<String, Object>() {
              {
                put(Tags.HTTP_METHOD, "GET");
                put(Tags.HTTP_ROUTE, "/greeting");
                put(Tags.HTTP_URL, "http://localhost:8080/greeting");
              }
            });

    processor.processTags(
        tags,
        mockContext,
        Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    verify(mockContext, never()).setResourceName(any(), anyByte());
    assertFalse(tags.containsKey(Tags.HTTP_ENDPOINT));
  }

  @Test
  void shouldComputeAndTagHttpEndpointFromUrlWhenRouteIsInvalid() {
    EndpointResolver endpointResolver = new EndpointResolver(true, false);
    HttpEndpointPostProcessor processor = new HttpEndpointPostProcessor(endpointResolver);
    TagMap tags =
        TagMap.fromMap(
            new java.util.LinkedHashMap<String, Object>() {
              {
                put(Tags.HTTP_METHOD, "GET");
                put(Tags.HTTP_ROUTE, "*");
                put(Tags.HTTP_URL, "http://localhost:8080/users/123/orders/456");
              }
            });

    processor.processTags(
        tags,
        mockContext,
        Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    verify(mockContext, never()).setResourceName(any(), anyByte());
    assertEquals("/users/{param:int}/orders/{param:int}", tags.get(Tags.HTTP_ENDPOINT));
  }

  @Test
  void shouldSkipNonHttpSpans() {
    EndpointResolver endpointResolver = new EndpointResolver(true, false);
    HttpEndpointPostProcessor processor = new HttpEndpointPostProcessor(endpointResolver);
    TagMap tags =
        TagMap.fromMap(
            Collections.<String, Object>singletonMap("db.statement", "SELECT * FROM users"));

    processor.processTags(
        tags,
        mockContext,
        Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    verify(mockContext, never()).setResourceName(any(), anyByte());
    assertFalse(tags.containsKey(Tags.HTTP_ENDPOINT));
  }

  @Test
  void shouldNotProcessWhenResourceRenamingIsDisabled() {
    EndpointResolver endpointResolver = new EndpointResolver(false, false);
    HttpEndpointPostProcessor processor = new HttpEndpointPostProcessor(endpointResolver);
    TagMap tags =
        TagMap.fromMap(
            new java.util.LinkedHashMap<String, Object>() {
              {
                put(Tags.HTTP_METHOD, "GET");
                put(Tags.HTTP_ROUTE, "/greeting");
              }
            });

    processor.processTags(
        tags,
        mockContext,
        Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    verify(mockContext, never()).setResourceName(any(), anyByte());
    assertFalse(tags.containsKey(Tags.HTTP_ENDPOINT));
  }

  @Test
  void shouldTagHttpEndpointFromUrlWhenAlwaysSimplifiedIsTrue() {
    EndpointResolver endpointResolver = new EndpointResolver(true, true);
    HttpEndpointPostProcessor processor = new HttpEndpointPostProcessor(endpointResolver);
    TagMap tags =
        TagMap.fromMap(
            new java.util.LinkedHashMap<String, Object>() {
              {
                put(Tags.HTTP_METHOD, "GET");
                put(Tags.HTTP_ROUTE, "/greeting");
                put(Tags.HTTP_URL, "http://localhost:8080/users/123");
              }
            });

    processor.processTags(
        tags,
        mockContext,
        Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    verify(mockContext, never()).setResourceName(any(), anyByte());
    assertEquals("/users/{param:int}", tags.get(Tags.HTTP_ENDPOINT));
  }
}
