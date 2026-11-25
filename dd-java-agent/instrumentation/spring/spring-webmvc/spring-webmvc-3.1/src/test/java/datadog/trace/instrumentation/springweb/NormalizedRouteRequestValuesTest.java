package datadog.trace.instrumentation.springweb;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Request values the decorator reads for {@code _dd.appsec.normalized_route}, on the Servlet 3 /
 * Spring 4 test classpath (no {@code HttpServletMapping}, no {@code RequestPath}).
 */
class NormalizedRouteRequestValuesTest {

  private boolean appSecActive;

  @BeforeEach
  void activateAppSec() {
    appSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
  }

  @AfterEach
  void restoreAppSec() {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActive;
  }

  @Test
  void includeAttributesDescribeTheIncludedRequest() {
    MockHttpServletRequest request = request("/ctx/main", "/ctx", "/main", null);
    request.setAttribute("javax.servlet.include.request_uri", "/ctx/api/static/a");
    request.setAttribute("javax.servlet.include.context_path", "/ctx");
    request.setAttribute("javax.servlet.include.servlet_path", "/api");
    request.setAttribute("javax.servlet.include.path_info", "/static/a");

    assertTag(request, "/static/**", "/ctx/api/static/{param1}");
  }

  @Test
  void includeAttributesWithTheCachedLookupPath() {
    MockHttpServletRequest request = request("/ctx/main", "/ctx", "/main", null);
    request.setAttribute("javax.servlet.include.request_uri", "/ctx/api/a");
    request.setAttribute("javax.servlet.include.context_path", "/ctx");
    request.setAttribute("javax.servlet.include.servlet_path", "/api");
    request.setAttribute("javax.servlet.include.path_info", "/a");
    request.setAttribute("org.springframework.web.util.UrlPathHelper.PATH", "/a");

    assertTag(request, "/a", "/ctx/api/a");
  }

  @Test
  void requestPathAttributeOfAnotherTypeIsIgnored() {
    MockHttpServletRequest request = request("/ctx/static/a", "/ctx", "/static/a", null);
    request.setAttribute("org.springframework.web.util.ServletRequestPathUtils.PATH", "/static/a");

    assertTag(request, "/static/**", "/ctx/static/{param1}");
  }

  @Test
  void withoutServletMappingTheExactMountUrlIsNotAMount() {
    // Servlet 3: no HttpServletMapping, pathInfo null keeps the previous rule
    MockHttpServletRequest request = request("/ctx/api", "/ctx", "/api", null);
    request.setAttribute(
        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Collections.singletonMap("rest", ""));

    assertTag(request, "/api/{*rest}", "/ctx/api");
  }

  @Test
  void contextPathParametersAreNotEmitted() {
    MockHttpServletRequest request =
        request("/ctx;jsessionid=AB/api/a", "/ctx;jsessionid=AB", "/api", "/a");

    assertTag(request, "/a", "/ctx/api/a");
  }

  @Test
  void neverThrowsOnABrokenRequest() {
    MockHttpServletRequest request =
        new MockHttpServletRequest() {
          @Override
          public String getRequestURI() {
            throw new IllegalStateException("broken");
          }
        };
    AgentSpan span = span();

    SpringWebHttpServerDecorator.tagNormalizedRoute(span, request, "/a/{id}");

    verify(span, never()).setTag(anyString(), anyString());
  }

  private static MockHttpServletRequest request(
      String requestUri, String contextPath, String servletPath, String pathInfo) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
    request.setContextPath(contextPath);
    request.setServletPath(servletPath);
    request.setPathInfo(pathInfo);
    return request;
  }

  private static AgentSpan span() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(HTTP_ROUTE)).thenReturn("route");
    return span;
  }

  private static void assertTag(MockHttpServletRequest request, String pattern, String expected) {
    AgentSpan span = span();

    SpringWebHttpServerDecorator.tagNormalizedRoute(span, request, pattern);

    verify(span).setTag(NORMALIZED_ROUTE_TAG, expected);
  }
}
