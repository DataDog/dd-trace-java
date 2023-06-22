package foo.bar.smoketest;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;

public class HttpServlet3TestSuite implements ServletSuite<HttpServletRequest> {

  @Override
  public Map<String, String[]> getParameterMap(HttpServletRequest request) {
    return request.getParameterMap();
  }
}
