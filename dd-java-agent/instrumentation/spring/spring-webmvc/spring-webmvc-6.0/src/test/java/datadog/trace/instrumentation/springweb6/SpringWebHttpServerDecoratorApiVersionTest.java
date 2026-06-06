package datadog.trace.instrumentation.springweb6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests for the {@code http.api_version} span tag added by {@link SpringWebHttpServerDecorator}.
 *
 * <p>Unlike {@link SpringWebHttpServerDecoratorTest}, these tests use Spring's {@link
 * MockHttpServletRequest} to supply a realistic request object and record tag writes via a
 * recording answer on the span mock — asserting on the actual stored tag <em>value</em> rather
 * than just verifying that {@code setTag} was called.
 */
class SpringWebHttpServerDecoratorApiVersionTest {

  private static final String API_VERSION_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.apiVersion";

  private AgentSpan span;
  private Map<String, Object> capturedTags;

  @BeforeEach
  void setup() {
    capturedTags = new HashMap<>();
    span = mock(AgentSpan.class);
    when(span.setTag(anyString(), anyString())).thenAnswer(this::captureTag);
  }

  private AgentSpan captureTag(InvocationOnMock invocation) {
    capturedTags.put(invocation.getArgument(0), invocation.getArgument(1));
    return span;
  }

  /**
   * When the request carries the Spring Framework 7 api-version attribute, {@code
   * SpringWebHttpServerDecorator.onRequest()} must write {@code http.api_version} on the span and
   * the stored value must equal the attribute string.
   */
  @Test
  void onRequest_setsHttpApiVersionTag_whenAttributePresent() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/items");
    request.setAttribute(API_VERSION_ATTRIBUTE, "v2");

    SpringWebHttpServerDecorator.DECORATE.onRequest(span, request, request, null);

    assertEquals(
        "v2",
        capturedTags.get("http.api_version"),
        "http.api_version tag must reflect the value of the HandlerMapping.apiVersion attribute");
  }

  /**
   * When the attribute is absent the tag must not be written at all — a missing key in {@code
   * capturedTags} is the proof.
   */
  @Test
  void onRequest_doesNotSetHttpApiVersionTag_whenAttributeAbsent() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/items");
    // No API_VERSION_ATTRIBUTE set

    SpringWebHttpServerDecorator.DECORATE.onRequest(span, request, request, null);

    assertNull(
        capturedTags.get("http.api_version"),
        "http.api_version must not be tagged when the attribute is missing");
  }

  /**
   * An empty string is treated the same as absent: no tag must be written.
   */
  @Test
  void onRequest_doesNotSetHttpApiVersionTag_whenAttributeIsEmpty() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/items");
    request.setAttribute(API_VERSION_ATTRIBUTE, "");

    SpringWebHttpServerDecorator.DECORATE.onRequest(span, request, request, null);

    assertNull(
        capturedTags.get("http.api_version"),
        "http.api_version must not be tagged when the attribute value is empty");
  }

  /**
   * A non-String attribute (e.g., an enum or domain object) must not cause an exception and must
   * not write the tag.
   */
  @Test
  void onRequest_doesNotSetHttpApiVersionTag_whenAttributeIsNonString() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/items");
    request.setAttribute(API_VERSION_ATTRIBUTE, Integer.valueOf(2));

    SpringWebHttpServerDecorator.DECORATE.onRequest(span, request, request, null);

    assertNull(
        capturedTags.get("http.api_version"),
        "http.api_version must not be tagged when the attribute is a non-String type");
  }
}
