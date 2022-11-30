package foo.bar.smoketest;

import javax.servlet.http.HttpServletRequest;

public class Servlet3TestSuite {
  HttpServletRequest request;

  public Servlet3TestSuite(HttpServletRequest request) {
    this.request = request;
  }

  public java.util.Map<java.lang.String, java.lang.String[]> getParameterMap() {
    return request.getParameterMap();
  }
}
