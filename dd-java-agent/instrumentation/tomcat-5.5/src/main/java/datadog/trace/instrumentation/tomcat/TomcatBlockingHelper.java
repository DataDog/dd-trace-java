package datadog.trace.instrumentation.tomcat;

import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TomcatBlockingHelper {
  private static final Logger log = LoggerFactory.getLogger(TomcatBlockingHelper.class);
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

  public static void commitBlockingResponse(
      Request request, Response resp, Flow.Action.RequestBlockingAction rba) {
    int httpCode = BlockingActionHelper.getHttpCode(rba.getStatusCode());
    if (!start(resp, httpCode) || GET_OUTPUT_STREAM == null) {
      return;
    }

    TemplateType type =
        BlockingActionHelper.determineTemplateType(
            rba.getBlockingContentType(), request.getHeader("Accept"));
    byte[] template = BlockingActionHelper.getTemplate(type);

    resp.setHeader("Content-length", Integer.toString(template.length));
    resp.setHeader("Content-type", BlockingActionHelper.getContentType(type));
    try {
      OutputStream os = (OutputStream) GET_OUTPUT_STREAM.invoke(resp);
      os.write(template);
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
