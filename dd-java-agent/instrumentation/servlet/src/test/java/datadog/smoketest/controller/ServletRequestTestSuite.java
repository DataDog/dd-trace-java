package datadog.smoketest.controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Enumeration;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletInputStream;

public interface ServletRequestTestSuite<E> {

  void init(final E request);

  String getParameter(String paramName);

  String[] getParameterValues(String paramName);

  Enumeration getParameterNames();

  RequestDispatcher getRequestDispatcher(String path);

  ServletInputStream getInputStream() throws IOException;

  BufferedReader getReader() throws IOException;
}
