package datadog.smoketest.controller;

import java.util.Enumeration;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

public class TestHttpServletRequestCallSiteSuite {

  private HttpServletRequest request;

  public TestHttpServletRequestCallSiteSuite(final HttpServletRequest request) {
    this.request = request;
  }

  public String getHeader(final String headerName) {
    return request.getHeader(headerName);
  }

  public Enumeration<?> getHeaders(final String headerName) {
    return request.getHeaders(headerName);
  }

  public Enumeration<?> getHeaderNames() {
    return request.getHeaderNames();
  }

  public Cookie[] getCookies() {
    return request.getCookies();
  }

  public String getQueryString() {
    return request.getQueryString();
  }
}
