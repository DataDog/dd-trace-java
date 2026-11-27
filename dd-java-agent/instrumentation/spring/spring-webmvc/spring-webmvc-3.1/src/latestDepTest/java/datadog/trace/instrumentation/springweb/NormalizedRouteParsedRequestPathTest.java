package datadog.trace.instrumentation.springweb;

import static datadog.trace.api.KnownTags.DD_APPSEC_NORMALIZED_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.RequestPath;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Mount prefix from a real Spring 5.3+ {@code RequestPath} (PathPatternParser mode): the prefix is
 * what Spring excluded from the path it matched, whatever the servlet mapping says.
 */
class NormalizedRouteParsedRequestPathTest {

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
  void servlet3PathMappingKeepsTheServletPathInTheMatchedPath() {
    // Servlet 3.1: Spring applies no servlet mount, the pattern includes the servlet path
    MockHttpServletRequest request = request("/ctx/api/users/42", "/ctx", "/api", "/users/42");
    request.setAttribute(
        ServletRequestPathUtils.PATH_ATTRIBUTE, RequestPath.parse("/ctx/api/users/42", "/ctx"));

    assertTag(request, "/api/users/{id}", "/ctx/api/users/{id}");
  }

  @Test
  void servlet3PathMappingCatchAllRemainder() {
    MockHttpServletRequest request = request("/ctx/api/static/a", "/ctx", "/api", "/static/a");
    request.setAttribute(
        ServletRequestPathUtils.PATH_ATTRIBUTE, RequestPath.parse("/ctx/api/static/a", "/ctx"));

    assertTag(request, "/api/static/**", "/ctx/api/static/{param1}");
  }

  @Test
  void servlet4PathMappingIsExcludedBySpring() {
    MockHttpServletRequest request = request("/ctx/api/users/42", "/ctx", "/api", "/users/42");
    // Servlet 4+ path mapping: Spring parses with the servlet path as part of the prefix
    request.setAttribute(
        ServletRequestPathUtils.PATH_ATTRIBUTE, RequestPath.parse("/ctx/api/users/42", "/ctx/api"));

    assertTag(request, "/users/{id}", "/ctx/api/users/{id}");
  }

  @Test
  void servlet4ExactMountUrlOfAPathMapping() {
    MockHttpServletRequest request = request("/ctx/api", "/ctx", "/api", null);
    request.setAttribute(
        ServletRequestPathUtils.PATH_ATTRIBUTE, RequestPath.parse("/ctx/api", "/ctx/api"));
    request.setAttribute(
        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Collections.singletonMap("rest", ""));

    assertTag(request, "/{*rest}", "/ctx/api");
  }

  @Test
  void contextPathParametersAreNotEmitted() {
    MockHttpServletRequest request =
        request("/ctx;jsessionid=AB/api/a", "/ctx;jsessionid=AB", "/api", "/a");
    request.setAttribute(
        ServletRequestPathUtils.PATH_ATTRIBUTE,
        RequestPath.parse("/ctx;jsessionid=AB/api/a", "/ctx;jsessionid=AB"));

    assertTag(request, "/api/a", "/ctx/api/a");
  }

  private static MockHttpServletRequest request(
      String requestUri, String contextPath, String servletPath, String pathInfo) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
    request.setContextPath(contextPath);
    request.setServletPath(servletPath);
    request.setPathInfo(pathInfo);
    return request;
  }

  private static void assertTag(MockHttpServletRequest request, String pattern, String expected) {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(HTTP_ROUTE)).thenReturn("route");

    SpringWebHttpServerDecorator.tagNormalizedRoute(span, request, pattern);

    verify(span).setTag(DD_APPSEC_NORMALIZED_ROUTE, expected);
  }
}
