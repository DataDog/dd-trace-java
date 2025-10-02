package foo.bar.smoketest;

import jakarta.servlet.http.HttpServletRequestWrapper;

public class JakartaHttpServletRequestWrapperTestSuite implements ServletRequestTestSuite {
  private final HttpServletRequestWrapper request;

  public JakartaHttpServletRequestWrapperTestSuite(final HttpServletRequestWrapper request) {
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
