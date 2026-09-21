package datadog.trace.instrumentation.springweb;

import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.withContextPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class SpringWebHttpServerDecoratorTest {
  @Test
  void returnsSamePatternForNullContextPath() {
    String pattern = "/save";

    assertSame(pattern, withContextPath(requestWithContextPath(null), pattern));
  }

  @Test
  void returnsSamePatternForEmptyContextPath() {
    String pattern = "/save";

    assertSame(pattern, withContextPath(requestWithContextPath(""), pattern));
  }

  @Test
  void returnsSamePatternForRootContextPath() {
    String pattern = "/save";

    assertSame(pattern, withContextPath(requestWithContextPath("/"), pattern));
  }

  @Test
  void prefixesPatternWithContextPath() {
    assertEquals("/cache/save", withContextPath(requestWithContextPath("/cache"), "/save"));
  }

  @Test
  void preservesMatchingLeadingSegmentInPattern() {
    assertEquals(
        "/cache/cache/save", withContextPath(requestWithContextPath("/cache"), "/cache/save"));
  }

  @Test
  void prefixesPathParameterPattern() {
    assertEquals(
        "/cache/items/{id}", withContextPath(requestWithContextPath("/cache"), "/items/{id}"));
  }

  private static HttpServletRequest requestWithContextPath(String contextPath) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContextPath()).thenReturn(contextPath);
    return request;
  }
}
