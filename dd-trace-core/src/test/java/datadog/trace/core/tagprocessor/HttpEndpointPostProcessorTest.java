package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.api.TagMap;
import datadog.trace.api.endpoint.EndpointResolver;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HttpEndpointPostProcessorTest {

  @Mock DDSpanContext mockContext;
  @Mock AppendableSpanLinks mockSpanLinks;

  @Test
  void shouldNotOverwriteResourceNameWhenHttpRouteIsAvailableAndEligible() {
    EndpointResolver endpointResolver = new EndpointResolver(true, false);
    HttpEndpointPostProcessor processor = new HttpEndpointPostProcessor(endpointResolver);
    Map<String, String> tagInput = new HashMap<>();
    tagInput.put(Tags.HTTP_METHOD, "GET");
    tagInput.put(Tags.HTTP_ROUTE, "/greeting");
    tagInput.put(Tags.HTTP_URL, "http://localhost:8080/greeting");
    TagMap tags = TagMap.fromMap(tagInput);

    processor.processTags(tags, mockContext, mockSpanLinks);

    verify(mockContext, never()).setResourceName(any(CharSequence.class), anyByte());
    assertFalse(tags.containsKey(Tags.HTTP_ENDPOINT));
  }

  @Test
  void shouldComputeAndTagHttpEndpointFromUrlWhenRouteIsInvalidWithoutTouchingResourceName() {
    EndpointResolver endpointResolver = new EndpointResolver(true, false);
    HttpEndpointPostProcessor processor = new HttpEndpointPostProcessor(endpointResolver);
    Map<String, String> tagInput = new HashMap<>();
    tagInput.put(Tags.HTTP_METHOD, "GET");
    tagInput.put(Tags.HTTP_ROUTE, "*"); // catch-all — ineligible per RFC-1051
    tagInput.put(Tags.HTTP_URL, "http://localhost:8080/users/123/orders/456");
    TagMap tags = TagMap.fromMap(tagInput);

    processor.processTags(tags, mockContext, mockSpanLinks);

    verify(mockContext, never()).setResourceName(any(CharSequence.class), anyByte());
    assertEquals("/users/{param:int}/orders/{param:int}", tags.get(Tags.HTTP_ENDPOINT));
  }

  @Test
  void shouldSkipNonHttpSpans() {
    EndpointResolver endpointResolver = new EndpointResolver(true, false);
    HttpEndpointPostProcessor processor = new HttpEndpointPostProcessor(endpointResolver);
    Map<String, String> tagInput = new HashMap<>();
    tagInput.put("db.statement", "SELECT * FROM users");
    TagMap tags = TagMap.fromMap(tagInput);

    processor.processTags(tags, mockContext, mockSpanLinks);

    verify(mockContext, never()).setResourceName(any(CharSequence.class), anyByte());
    assertFalse(tags.containsKey(Tags.HTTP_ENDPOINT));
  }

  @Test
  void shouldNotProcessWhenResourceRenamingIsDisabled() {
    EndpointResolver endpointResolver = new EndpointResolver(false, false);
    HttpEndpointPostProcessor processor = new HttpEndpointPostProcessor(endpointResolver);
    Map<String, String> tagInput = new HashMap<>();
    tagInput.put(Tags.HTTP_METHOD, "GET");
    tagInput.put(Tags.HTTP_ROUTE, "/greeting");
    TagMap tags = TagMap.fromMap(tagInput);

    processor.processTags(tags, mockContext, mockSpanLinks);

    verify(mockContext, never()).setResourceName(any(CharSequence.class), anyByte());
    assertFalse(tags.containsKey(Tags.HTTP_ENDPOINT));
  }

  @Test
  void shouldTagHttpEndpointFromUrlEvenWhenAlwaysSimplifiedIsTrueWithoutTouchingResourceName() {
    EndpointResolver endpointResolver = new EndpointResolver(true, true);
    HttpEndpointPostProcessor processor = new HttpEndpointPostProcessor(endpointResolver);
    Map<String, String> tagInput = new HashMap<>();
    tagInput.put(Tags.HTTP_METHOD, "GET");
    tagInput.put(Tags.HTTP_ROUTE, "/greeting");
    tagInput.put(Tags.HTTP_URL, "http://localhost:8080/users/123");
    TagMap tags = TagMap.fromMap(tagInput);

    processor.processTags(tags, mockContext, mockSpanLinks);

    verify(mockContext, never()).setResourceName(any(CharSequence.class), anyByte());
    assertEquals("/users/{param:int}", tags.get(Tags.HTTP_ENDPOINT));
  }
}
