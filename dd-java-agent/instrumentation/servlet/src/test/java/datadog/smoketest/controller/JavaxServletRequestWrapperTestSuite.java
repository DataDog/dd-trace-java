package datadog.smoketest.controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Enumeration;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequestWrapper;

public class JavaxServletRequestWrapperTestSuite
    implements ServletRequestTestSuite<ServletRequestWrapper> {
  ServletRequestWrapper request;

  @Override
  public void init(ServletRequestWrapper request) {
    this.request = request;
  }

  @Override
  public String getParameter(String paramName) {
    return request.getParameter(paramName);
  }

  @Override
  public String[] getParameterValues(String paramName) {
    return request.getParameterValues(paramName);
  }

  @Override
  public Enumeration getParameterNames() {
    return request.getParameterNames();
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    return request.getInputStream();
  }

  @Override
  public BufferedReader getReader() throws IOException {
    return request.getReader();
  }

  @Override
  public RequestDispatcher getRequestDispatcher(String path) {
    return request.getRequestDispatcher(path);
  }
}
