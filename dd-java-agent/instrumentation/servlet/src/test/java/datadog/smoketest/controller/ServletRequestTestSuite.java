package datadog.smoketest.controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Enumeration;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;

public class ServletRequestTestSuite {
  ServletRequest request;

  public ServletRequestTestSuite(ServletRequest request) {
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

  public ServletInputStream getInputStream() throws IOException {
    return request.getInputStream();
  }

  public BufferedReader getReader() throws IOException {
    return request.getReader();
  }
}
