package datadog.trace.instrumentation.servlet;

import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServletBlockingHelper {
  private static final MethodHandle DEBUG_MH;
  private static final MethodHandle WARN_MH;
  private static final MethodHandle WARN_THR_MH;

  static {
    MethodHandle debugMH = null, warnMH = null, warnThrMH = null;
    try {
      Logger log = LoggerFactory.getLogger(ServletBlockingHelper.class);
      debugMH =
          MethodHandles.lookup()
              .findVirtual(Logger.class, "debug", MethodType.methodType(void.class, String.class));
      debugMH = debugMH.bindTo(log);
      warnMH =
          MethodHandles.lookup()
              .findVirtual(Logger.class, "warn", MethodType.methodType(void.class, String.class));
      warnMH = warnMH.bindTo(log);
      warnThrMH =
          MethodHandles.lookup()
              .findVirtual(
                  Logger.class,
                  "warn",
                  MethodType.methodType(void.class, String.class, Throwable.class));
      warnThrMH = warnThrMH.bindTo(log);
    } catch (NoClassDefFoundError | NoSuchMethodException | IllegalAccessException err) {
    }
    DEBUG_MH = debugMH;
    WARN_MH = warnMH;
    WARN_THR_MH = warnThrMH;
  }

  public static void commitBlockingResponse(
      HttpServletRequest httpServletRequest,
      HttpServletResponse resp,
      Flow.Action.RequestBlockingAction rba) {
    int statusCode = BlockingActionHelper.getHttpCode(rba.getStatusCode());
    if (!start(resp, statusCode)) {
      return;
    }

    String acceptHeader = httpServletRequest.getHeader("Accept");
    TemplateType type =
        BlockingActionHelper.determineTemplateType(rba.getBlockingContentType(), acceptHeader);
    byte[] template = BlockingActionHelper.getTemplate(type);
    String contentType = BlockingActionHelper.getContentType(type);

    resp.setHeader("Content-length", Integer.toString(template.length));
    resp.setHeader("Content-type", contentType);
    try {
      OutputStream os = resp.getOutputStream();
      os.write(template);
      os.close();
    } catch (IOException e) {
      warn("Error sending error page", e);
    }
  }

  private static void warn(String msg, Throwable t) {
    if (WARN_THR_MH == null) {
      return;
    }

    try {
      WARN_THR_MH.invoke(msg, t);
    } catch (Throwable ex) {
    }
  }

  private static void warn(String msg) {
    if (WARN_MH == null) {
      return;
    }

    try {
      WARN_MH.invoke(msg);
    } catch (Throwable ex) {
    }
  }

  private static void debug(String msg) {
    if (DEBUG_MH == null) {
      return;
    }

    try {
      DEBUG_MH.invoke(msg);
    } catch (Throwable ex) {
    }
  }

  private static boolean start(HttpServletResponse resp, int statusCode) {
    if (resp.isCommitted()) {
      warn("response already committed, we can't change it");
    }

    debug("Committing blocking response");

    resp.reset();
    resp.setStatus(statusCode);

    return true;
  }
}
