package datadog.trace.instrumentation.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServletBlockingHelper {
  private static final Logger log = LoggerFactory.getLogger(ServletBlockingHelper.class);
  private static final int STATUS_CODE = 403;
  private static final String RESPONSE_TEXT = "Access denied (request blocked)";
  private static final byte[] RESPONSE_BODY = RESPONSE_TEXT.getBytes(StandardCharsets.US_ASCII);

  public static void commitBlockingResponse(HttpServletResponse resp) {
    if (!start(resp, STATUS_CODE)) {
      return;
    }

    resp.setHeader("Content-length", Integer.toString(RESPONSE_BODY.length));
    resp.setHeader("Content-type", "text/plain");
    try {
      OutputStream os = resp.getOutputStream();
      os.write(RESPONSE_BODY);
      os.close();
    } catch (IOException e) {
      log.warn("Error sending error page", e);
    }
  }

  private static boolean start(HttpServletResponse resp, int statusCode) {
    if (resp.isCommitted()) {
      log.warn("response already committed, we can't change it");
      return false;
    }

    log.debug("Committing blocking response");

    resp.reset();
    resp.setStatus(statusCode);

    return true;
  }
}
