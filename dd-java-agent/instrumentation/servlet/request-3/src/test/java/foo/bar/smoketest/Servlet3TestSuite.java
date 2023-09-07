package foo.bar.smoketest;

import java.util.Map;
import javax.servlet.ServletRequest;

public class Servlet3TestSuite implements ServletSuite<ServletRequest> {

  @Override
  public Map<String, String[]> getParameterMap(ServletRequest request) {
    return request.getParameterMap();
  }
}
