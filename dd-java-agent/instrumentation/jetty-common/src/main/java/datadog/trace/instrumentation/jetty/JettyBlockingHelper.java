package datadog.trace.instrumentation.jetty;

import static java.lang.invoke.MethodType.methodType;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Map;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyBlockingHelper {
  private static final Logger log = LoggerFactory.getLogger(JettyBlockingHelper.class);
  private static final MethodHandle GET_OUTPUT_STREAM;
  private static final MethodHandle CLOSE_OUTPUT;
  private static final MethodHandle GET_ASYNC_CONTEXT;
  private static final MethodHandle COMPLETE;
  private static final MethodHandle IS_ASYNC_STARTED;
  private static final boolean INITIALIZED;

  static {
    MethodHandle getOutputStreamMH = null;
    MethodHandle closeOutputMH = null;
    MethodHandle getAsyncContextMH = null;
    MethodHandle completeMH = null;
    MethodHandle isAsyncStartedMH = null;
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
    try {
      // return value varies between versions
      Method getAsyncContext = Request.class.getMethod("getAsyncContext");
      getAsyncContextMH = MethodHandles.lookup().unreflect(getAsyncContext);
      completeMH =
          MethodHandles.lookup()
              .findVirtual(
                  getAsyncContextMH.type().returnType(), "complete", methodType(void.class));
    } catch (IllegalAccessException | NoSuchMethodException | RuntimeException e) {
      log.error("Lookup of getAsyncContext failed. Will be unable to commit blocking response", e);
    }
    try {
      isAsyncStartedMH =
          MethodHandles.lookup()
              .findVirtual(Request.class, "isAsyncStarted", methodType(boolean.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      log.debug("Could not find " + Request.class.getName() + "#isAsyncStarted()");

      try {
        Class<?> asyncContinuationCls =
            Class.forName(
                "org.eclipse.jetty.server.AsyncContinuation",
                true,
                JettyBlockingHelper.class.getClassLoader());
        MethodHandle getAsyncContinuation =
            MethodHandles.lookup()
                .findVirtual(
                    Request.class, "getAsyncContinuation", methodType(asyncContinuationCls));
        MethodHandle isAsyncStarted =
            MethodHandles.lookup()
                .findVirtual(asyncContinuationCls, "isAsyncStarted", methodType(boolean.class));
        isAsyncStartedMH = MethodHandles.filterArguments(isAsyncStarted, 0, getAsyncContinuation);
      } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException ex) {
        log.error(
            "Could not build method handle for calling request.getAsyncContinuation.isAsyncStarted. "
                + "Will be unable to commit blocking response",
            e);
      }
    }

    GET_OUTPUT_STREAM = getOutputStreamMH;
    CLOSE_OUTPUT = closeOutputMH;
    GET_ASYNC_CONTEXT = getAsyncContextMH;
    COMPLETE = completeMH;
    IS_ASYNC_STARTED = isAsyncStartedMH;
    INITIALIZED =
        getAsyncContextMH != null
            && closeOutputMH != null
            && getAsyncContextMH != null
            && completeMH != null
            && isAsyncStartedMH != null;
  }

  private JettyBlockingHelper() {}

  public static boolean block(
      Request request,
      Response response,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders) {
    if (!INITIALIZED) {
      return false;
    }
    if (response.isCommitted()) {
      return true;
    }
    try {
      OutputStream os = (OutputStream) GET_OUTPUT_STREAM.invoke(response);
      response.setStatus(BlockingActionHelper.getHttpCode(statusCode));
      String acceptHeader = request.getHeader("Accept");

      for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
        response.setHeader(e.getKey(), e.getValue());
      }

      if (bct != BlockingContentType.NONE) {
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(bct, acceptHeader);
        response.setHeader("Content-type", BlockingActionHelper.getContentType(type));
        byte[] template = BlockingActionHelper.getTemplate(type);
        response.setHeader("Content-length", Integer.toString(template.length));
        os.write(template);
      }
      os.close();
      CLOSE_OUTPUT.invoke(response);
      try {
        if ((boolean) IS_ASYNC_STARTED.invoke(request)) {
          Object asyncContext = GET_ASYNC_CONTEXT.invoke(request);
          if (asyncContext != null) {
            COMPLETE.invoke(asyncContext);
          }
        }
      } catch (IllegalStateException ise) {
        log.debug("Error calling asyncContext.complete() conditioned on async started", ise);
      }
    } catch (Throwable e) {
      log.info("Error committing blocking response", e);
    }
    return true;
  }

  public static void block(
      Request request, Response response, Flow.Action.RequestBlockingAction rba) {
    block(
        request,
        response,
        rba.getStatusCode(),
        rba.getBlockingContentType(),
        rba.getExtraHeaders());
  }
}
