package test.normalizedroute;

/** Context path plus a path-mapped DispatcherServlet ({@code /api/*}): both are in the prefix. */
class NormalizedRouteServletPathTest extends NormalizedRouteTest {

  @Override
  String contextPath() {
    return "/ctx";
  }

  @Override
  String servletPath() {
    return "/api";
  }
}
