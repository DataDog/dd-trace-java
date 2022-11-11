package datadog.smoketest.controller;

import java.util.Enumeration;
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
}
