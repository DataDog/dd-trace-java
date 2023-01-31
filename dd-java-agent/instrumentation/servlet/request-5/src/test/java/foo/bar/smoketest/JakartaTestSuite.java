package foo.bar.smoketest;

import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Enumeration;

public class JakartaTestSuite {
  HttpServletRequestWrapper request;

  public JakartaTestSuite(HttpServletRequestWrapper request) {
    this.request = request;
  }

  public java.util.Map<String, String[]> getParameterMap() {
    return request.getParameterMap();
  }

  public String getParameter(String paramName) {
    return request.getParameter(paramName);
  }

  public String[] getParameterValues(String paramName) {
    return request.getParameterValues(paramName);
  }

  public Enumeration getParameterNames() {
    return request.getParameterNames();
  }
}
