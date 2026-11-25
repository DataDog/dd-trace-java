package datadog.trace.instrumentation.springweb6;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.http.MappingMatch;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/** Request values the decorator reads for {@code _dd.appsec.normalized_route}. */
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
  void parsedRequestPathIsTheRemainderLookupPath() {
    // the container removed the dot segments from the mapped path, Spring matched the raw path
    MockHttpServletRequest request = request("/ctx/static/x/..", "/ctx", "/static", null);
    request.setHttpServletMapping(
        new MockHttpServletMapping("", "", "dispatcher", MappingMatch.DEFAULT));
    ServletRequestPathUtils.parseAndCache(request);

    assertTag(request, "/static/**", "/ctx/static/{param1}");
  }

  @Test
  void includeAttributesDescribeTheIncludedRequest() {
    MockHttpServletRequest request = request("/ctx/main", "/ctx", "/main", null);
    request.setAttribute("jakarta.servlet.include.request_uri", "/ctx/api/static/a");
    request.setAttribute("jakarta.servlet.include.context_path", "/ctx");
    request.setAttribute("jakarta.servlet.include.servlet_path", "/api");
    request.setAttribute("jakarta.servlet.include.path_info", "/static/a");

    assertTag(request, "/static/**", "/ctx/api/static/{param1}");
  }

  @Test
  void includeMappingDecidesThePathMapping() {
    MockHttpServletRequest request = request("/ctx/main", "/ctx", "/main", null);
    request.setAttribute("jakarta.servlet.include.request_uri", "/ctx/api");
    request.setAttribute("jakarta.servlet.include.context_path", "/ctx");
    request.setAttribute("jakarta.servlet.include.servlet_path", "/api");
    request.setAttribute(
        "jakarta.servlet.include.mapping",
        new MockHttpServletMapping("api", "/api/*", "dispatcher", MappingMatch.PATH));
    ServletRequestPathUtils.parseAndCache(request);

    assertTag(request, "/a", "/ctx/api/a");
  }

  @Test
  void exactMountUrlOfAPathMappingKeepsTheServletPath() {
    MockHttpServletRequest request = request("/ctx/api", "/ctx", "/api", null);
    request.setHttpServletMapping(
        new MockHttpServletMapping("", "/api/*", "dispatcher", MappingMatch.PATH));
    ServletRequestPathUtils.parseAndCache(request);
    request.setAttribute(
        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Collections.singletonMap("rest", ""));

    assertTag(request, "/{*rest}", "/ctx/api");
  }

  @Test
  void withoutParsedRequestPathTheExactMountUrlMatchedThePathWithinTheApplication() {
    // UrlPathHelper returns the path within the application when nothing follows the servlet path
    MockHttpServletRequest request = request("/ctx/api", "/ctx", "/api", null);
    request.setHttpServletMapping(
        new MockHttpServletMapping("", "/api/*", "dispatcher", MappingMatch.PATH));

    assertTag(request, "/api", "/ctx/api");
  }

  @Test
  void parsedRequestPathRemainderIsMatchedWithoutSemicolonContentAndDecoded() {
    assertTag(parsedRequestPath("/ctx/static/;v=1"), "/static/**", "/ctx/static/");
    assertTag(parsedRequestPath("/ctx/static/a;v=1/b"), "/static/**", "/ctx/static/{param1}");
    assertTag(parsedRequestPath("/ctx/static/;v=1/x"), "/static/**", "/ctx/static/{param1}");
    // an encoded slash accepted by the container is only '/' once decoded
    assertTag(parsedRequestPath("/ctx/static/%2F"), "/static/**", "/ctx/static/");
    assertTag(parsedRequestPath("/ctx/static/%3Bfoo"), "/static/**", "/ctx/static/{param1}");
  }

  @Test
  void cachedLookupPathIsMatchedAsIs() {
    // decoded by UrlPathHelper: ';' from '%3B' is content
    assertTag(
        cachedLookupPath("/ctx/static/%3Bfoo", "/static/;foo"),
        "/static/**",
        "/ctx/static/{param1}");
    // removeSemicolonContent=false: AntPathMatcher matched the ';' parameters as content
    assertTag(
        cachedLookupPath("/ctx/static/;v=1", "/static/;v=1"), "/static/**", "/ctx/static/{param1}");
    assertTag(
        cachedLookupPath("/ctx/static/a;v=1/b", "/static/a;v=1/b"),
        "/static/**",
        "/ctx/static/{param1}");
  }

  @Test
  void catchAllRemainderKeepsTheSegmentPositionsOfTheCachedLookupPath() {
    // removeSemicolonContent=false: '{id}' matched ';v=1' and '**' matched 'x'
    assertTag(cachedLookupPath("/ctx/a/;v=1/x", "/a/;v=1/x"), "/a/{id}/**", "/ctx/a/{id}/{param1}");
  }

  @Test
  void exactUrlOfADefaultMappingIsNotAMount() {
    MockHttpServletRequest request = request("/ctx/api", "/ctx", "/api", null);
    request.setHttpServletMapping(
        new MockHttpServletMapping("api", "/", "dispatcher", MappingMatch.DEFAULT));

    assertTag(request, "/api", "/ctx/api");
  }

  @Test
  void contextPathParametersAreNotEmitted() {
    MockHttpServletRequest request =
        request("/ctx;jsessionid=AB/a", "/ctx;jsessionid=AB", "/a", null);

    assertTag(request, "/a", "/ctx/a");
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

  private static MockHttpServletRequest parsedRequestPath(String requestUri) {
    MockHttpServletRequest request = request(requestUri, "/ctx", "", null);
    request.setHttpServletMapping(
        new MockHttpServletMapping("", "/", "dispatcher", MappingMatch.DEFAULT));
    ServletRequestPathUtils.parseAndCache(request);
    return request;
  }

  private static MockHttpServletRequest cachedLookupPath(String requestUri, String lookupPath) {
    MockHttpServletRequest request = request(requestUri, "/ctx", lookupPath, null);
    request.setAttribute("org.springframework.web.util.UrlPathHelper.PATH", lookupPath);
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
