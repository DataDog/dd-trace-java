package datadog.trace.instrumentation.springweb6;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class SpringWebHttpServerDecoratorTest {

  private static final String API_VERSION_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.apiVersion";

  @Test
  void setsHttpApiVersionTagWhenAttributePresent() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    AgentSpan span = mock(AgentSpan.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getAttribute(API_VERSION_ATTRIBUTE)).thenReturn("v1");

    SpringWebHttpServerDecorator.DECORATE.onRequest(span, request, request, null);

    verify(span).setTag("http.api_version", "v1");
  }

  @Test
  void doesNotSetHttpApiVersionTagWhenAttributeAbsent() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    AgentSpan span = mock(AgentSpan.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getAttribute(API_VERSION_ATTRIBUTE)).thenReturn(null);

    SpringWebHttpServerDecorator.DECORATE.onRequest(span, request, request, null);

    verify(span, never()).setTag(eq("http.api_version"), anyString());
  }

  @Test
  void doesNotSetHttpApiVersionTagWhenAttributeEmpty() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    AgentSpan span = mock(AgentSpan.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getAttribute(API_VERSION_ATTRIBUTE)).thenReturn("");

    SpringWebHttpServerDecorator.DECORATE.onRequest(span, request, request, null);

    verify(span, never()).setTag(eq("http.api_version"), anyString());
  }
}
