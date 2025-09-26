package datadog.smoketest.controller;

import javax.servlet.http.HttpServletRequestWrapper;

public class JavaxHttpServletRequestWrapperTestSuite implements ServletRequestTestSuite {

  private final HttpServletRequestWrapper request;

  public JavaxHttpServletRequestWrapperTestSuite(final HttpServletRequestWrapper request) {
    this.request = request;
  }

  @Override
  public String getRequestURI() {
    return request.getRequestURI();
  }

  @Override
  public String getPathInfo() {
    return request.getPathInfo();
  }

  @Override
  public String getPathTranslated() {
    return request.getPathTranslated();
  }

  @Override
  public StringBuffer getRequestURL() {
    return request.getRequestURL();
  }
}
