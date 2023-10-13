package foo.bar.smoketest;

import java.util.Map;
import javax.servlet.ServletRequest;

public interface ServletSuite<S extends ServletRequest> {

  Map<String, String[]> getParameterMap(S request);
}
