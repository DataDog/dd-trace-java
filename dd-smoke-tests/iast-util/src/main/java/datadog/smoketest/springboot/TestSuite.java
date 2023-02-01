package datadog.smoketest.springboot;

import java.util.Enumeration;
import java.util.Map;
import javax.servlet.ServletRequest;

public class TestSuite {
  ServletRequest request;

  public TestSuite(ServletRequest request) {
    this.request = request;
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

  public Map<String, String[]> getParameterMap() {
    return request.getParameterMap();
  }
}
