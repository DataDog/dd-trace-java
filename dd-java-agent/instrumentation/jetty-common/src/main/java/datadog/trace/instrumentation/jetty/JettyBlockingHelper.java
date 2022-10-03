package datadog.trace.instrumentation.jetty;

import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyBlockingHelper {
  private static final Logger log = LoggerFactory.getLogger(JettyBlockingHelper.class);
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

  private JettyBlockingHelper() {}

  public static void block(
      Request request, Response response, Flow.Action.RequestBlockingAction rba) {
    if (GET_OUTPUT_STREAM != null && !response.isCommitted()) {
      try {
        OutputStream os = (OutputStream) GET_OUTPUT_STREAM.invoke(response);
        response.setStatus(BlockingActionHelper.getHttpCode(rba.getStatusCode()));
        String acceptHeader = request.getHeader("Accept");
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(rba.getBlockingContentType(), acceptHeader);
        response.setHeader("Content-type", BlockingActionHelper.getContentType(type));
        byte[] template = BlockingActionHelper.getTemplate(type);
        response.setHeader("Content-length", Integer.toString(template.length));
        os.write(template);
        os.close();
        response.complete();
      } catch (Throwable e) {
        log.info("Error committing blocking response", e);
      }
    }
  }
}
