package foo.bar.smoketest;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.util.Enumeration;

public class JakartaHttpServletRequestWrapperTestSuite
    implements ServletRequestTestSuite<HttpServletRequestWrapper> {
  HttpServletRequestWrapper request;

  @Override
  public void init(HttpServletRequestWrapper request) {
    this.request = request;
  }

  @Override
  public java.util.Map<String, String[]> getParameterMap() {
    return request.getParameterMap();
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
  public RequestDispatcher getRequestDispatcher(String path) {
    return request.getRequestDispatcher(path);
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    return request.getInputStream();
  }
}
