package foo.bar.smoketest;

import jakarta.servlet.http.HttpServletRequest;

public class JakartaHttpServletRequestTestSuite implements ServletRequestTestSuite {
  private final HttpServletRequest request;

  public JakartaHttpServletRequestTestSuite(final HttpServletRequest request) {
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
