package datadog.trace.instrumentation.springweb6.normalizedroute;

/** Non-root context path plus a path-mapped DispatcherServlet ({@code /api/*}). */
class NormalizedRouteServletPathTest extends NormalizedRouteTest {

  @Override
  String servletPath() {
    return "/api";
  }
}
