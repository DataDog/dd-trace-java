package datadog.trace.instrumentation.servlet6;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class RumHttpServletResponseWrapper60 extends RumHttpServletResponseWrapper {
  public RumHttpServletResponseWrapper60(HttpServletRequest request, HttpServletResponse response) {
    super(request, response);
  }

  @Override
  public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
    commit();
    super.sendRedirect(location, sc, clearBuffer);
  }
}
