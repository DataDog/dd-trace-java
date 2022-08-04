package datadog.trace.instrumentation.tomcat;

import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.connector.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TomcatBlockingHelper {
  private static final Logger log = LoggerFactory.getLogger(TomcatBlockingHelper.class);
  private static final int STATUS_CODE = 403;
  private static final String RESPONSE_TEXT = "Access denied (request blocked)";
  private static final byte[] RESPONSE_BODY = RESPONSE_TEXT.getBytes(StandardCharsets.US_ASCII);
  private static final MethodHandle GET_OUTPUT_STREAM;

  static {
    MethodHandle mh = null;
    try {
      Method getOutputStream = Response.class.getMethod("getOutputStream");
      mh = MethodHandles.lookup().unreflect(getOutputStream);
    } catch (IllegalAccessException | NoSuchMethodException e) {
      log.error("Lookup of getOutputStream failed. Will be unable to commit blocking response");
    }
    GET_OUTPUT_STREAM = mh;
  }

  public static void commitBlockingResponse(Response resp) {
    if (!start(resp, STATUS_CODE) || GET_OUTPUT_STREAM == null) {
      return;
    }

    resp.setHeader("Content-length", Integer.toString(RESPONSE_BODY.length));
    resp.setHeader("Content-type", "text/plain");
    try {
      OutputStream os = (OutputStream) GET_OUTPUT_STREAM.invoke(resp);
      os.write(RESPONSE_BODY);
      os.close();
    } catch (Throwable e) {
      log.info("Error sending error page", e);
    }
  }

  private static boolean start(Response resp, int statusCode) {
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
