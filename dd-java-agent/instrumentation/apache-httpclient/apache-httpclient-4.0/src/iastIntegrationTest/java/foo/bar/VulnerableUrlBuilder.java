package foo.bar;

import datadog.trace.agent.test.server.http.TestHttpServer.HandlerApi.RequestApi;

/** Class to be instrumented by IAST call sites containing methods to work with urls */
public abstract class VulnerableUrlBuilder {

  private VulnerableUrlBuilder() {}

  public static String url(RequestApi request) {
    final String url = (String) request.getParameter("url");
    if (url != null) {
      return url;
    }
    final String scheme = (String) request.getParameter("scheme");
    final boolean https = "https".equals(scheme);
    final String host = (String) request.getParameter("host");
    if (host != null) {
      return (https ? "https://" : "http://") + host + "/test?1=1";
    }
    final String path = (String) request.getParameter("path");
    if (path != null) {
      return (https ? "https://inexistent/" : "http://inexistent/") + path + "?1=1";
    }
    final String query = (String) request.getParameter("query");
    if (query != null) {
      return (https ? "https://inexistent/test" : "http://inexistent/test") + query;
    }
    return scheme + "://inexistent/test?1=1";
  }
}
