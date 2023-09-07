package foo.bar.smoketest;

import java.util.Map;
import javax.servlet.http.HttpServletRequestWrapper;

public class HttpServletWrapper3TestSuite implements ServletSuite<HttpServletRequestWrapper> {

  @Override
  public Map<String, String[]> getParameterMap(HttpServletRequestWrapper request) {
    return request.getParameterMap();
  }
}
