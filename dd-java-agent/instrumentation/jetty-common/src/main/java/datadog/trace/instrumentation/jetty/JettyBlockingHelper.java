package datadog.trace.instrumentation.jetty;

import static java.lang.invoke.MethodType.methodType;

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
  private static final MethodHandle CLOSE_OUTPUT;

  static {
    MethodHandle getOutputStreamMH = null;
    MethodHandle closeOutputMH = null;
    try {
      Method getOutputStream = Response.class.getMethod("getOutputStream");
      getOutputStreamMH = MethodHandles.lookup().unreflect(getOutputStream);
    } catch (IllegalAccessException | NoSuchMethodException | RuntimeException e) {
      log.error("Lookup of getOutputStream failed. Will be unable to commit blocking response", e);
    }
    try {
      closeOutputMH =
          MethodHandles.lookup().findVirtual(Response.class, "complete", methodType(void.class));
    } catch (IllegalAccessException | NoSuchMethodException | RuntimeException e) {
      try {
        closeOutputMH =
            MethodHandles.lookup()
                .findVirtual(Response.class, "closeOutput", methodType(void.class));
      } catch (IllegalAccessException | NoSuchMethodException | RuntimeException e2) {
        log.error(
            "Lookup of closeOutput/complete failed. Will be unable to commit blocking response",
            e2);
      }
    }

    GET_OUTPUT_STREAM = getOutputStreamMH;
    CLOSE_OUTPUT = closeOutputMH;
  }

  private JettyBlockingHelper() {}

  public static void block(
      Request request, Response response, Flow.Action.RequestBlockingAction rba) {
    if (GET_OUTPUT_STREAM != null && CLOSE_OUTPUT != null && !response.isCommitted()) {
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
        CLOSE_OUTPUT.invoke(response);
      } catch (Throwable e) {
        log.info("Error committing blocking response", e);
      }
    }
  }
}
