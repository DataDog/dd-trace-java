package datadog.trace.instrumentation.jetty;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static java.lang.invoke.MethodHandles.collectArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.context.Context;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.eclipse.jetty.io.EndPoint;
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
  private static final MethodHandle ABORT;
  private static final boolean INITIALIZED;

  static {
    MethodHandle getOutputStreamMH = null;
    MethodHandle closeOutputMH = null;
    MethodHandle getAsyncContextMH = null;
    MethodHandle completeMH = null;
    MethodHandle isAsyncStartedMH = null;
    MethodHandle abortMH = null;

    try {
      Method getOutputStream = Response.class.getMethod("getOutputStream");
      getOutputStreamMH = lookup().unreflect(getOutputStream);
    } catch (IllegalAccessException | NoSuchMethodException | RuntimeException e) {
      log.error("Lookup of getOutputStream failed. Will be unable to commit blocking response", e);
    }
    try {
      closeOutputMH = lookup().findVirtual(Response.class, "complete", methodType(void.class));
    } catch (IllegalAccessException | NoSuchMethodException | RuntimeException e) {
      try {
        closeOutputMH = lookup().findVirtual(Response.class, "closeOutput", methodType(void.class));
      } catch (IllegalAccessException | NoSuchMethodException | RuntimeException e2) {
        log.error(
            "Lookup of closeOutput/complete failed. Will be unable to commit blocking response",
            e2);
      }
    }
    try {
      // return value varies between versions
      Method getAsyncContext = Request.class.getMethod("getAsyncContext");
      getAsyncContextMH = lookup().unreflect(getAsyncContext);
      completeMH =
          lookup()
              .findVirtual(
                  getAsyncContextMH.type().returnType(), "complete", methodType(void.class));
    } catch (IllegalAccessException | NoSuchMethodException | RuntimeException e) {
      log.error("Lookup of getAsyncContext failed. Will be unable to commit blocking response", e);
    }
    try {
      isAsyncStartedMH =
          lookup().findVirtual(Request.class, "isAsyncStarted", methodType(boolean.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      log.debug("Could not find " + Request.class.getName() + "#isAsyncStarted()");

      try {
        Class<?> asyncContinuationCls =
            Class.forName(
                "org.eclipse.jetty.server.AsyncContinuation",
                true,
                JettyBlockingHelper.class.getClassLoader());
        MethodHandle getAsyncContinuation =
            lookup()
                .findVirtual(
                    Request.class, "getAsyncContinuation", methodType(asyncContinuationCls));
        MethodHandle isAsyncStarted =
            lookup().findVirtual(asyncContinuationCls, "isAsyncStarted", methodType(boolean.class));
        isAsyncStartedMH = MethodHandles.filterArguments(isAsyncStarted, 0, getAsyncContinuation);
      } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException ex) {
        log.error(
            "Could not build method handle for calling request.getAsyncContinuation.isAsyncStarted. "
                + "Will be unable to commit blocking response",
            e);
      }
    }
    try {
      Class<?> httpConnectionCls;
      try {
        httpConnectionCls =
            Class.forName(
                "org.eclipse.jetty.server.AbstractHttpConnection",
                true,
                JettyBlockingHelper.class.getClassLoader());
      } catch (ClassNotFoundException cnfe) {
        httpConnectionCls =
            Class.forName(
                "org.eclipse.jetty.server.HttpConnection",
                true,
                JettyBlockingHelper.class.getClassLoader());
      }
      MethodHandle getConnection =
          lookup().findVirtual(Request.class, "getConnection", methodType(httpConnectionCls));
      MethodHandle getEndPoint =
          lookup()
              .findVirtual(httpConnectionCls, "getEndPoint", MethodType.methodType(EndPoint.class));
      MethodHandle close = lookup().findVirtual(EndPoint.class, "close", methodType(void.class));
      abortMH = collectArguments(collectArguments(close, 0, getEndPoint), 0, getConnection);
    } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
      try {
        Class<?> httpChannelCls =
            Class.forName(
                "org.eclipse.jetty.server.HttpChannel",
                true,
                JettyBlockingHelper.class.getClassLoader());
        MethodHandle getHttpChannel =
            lookup().findVirtual(Request.class, "getHttpChannel", methodType(httpChannelCls));
        MethodHandle getEndPoint =
            lookup().findVirtual(httpChannelCls, "getEndPoint", methodType(EndPoint.class));
        MethodHandle close = lookup().findVirtual(EndPoint.class, "close", methodType(void.class));
        abortMH = collectArguments(collectArguments(close, 0, getEndPoint), 0, getHttpChannel);
      } catch (ClassNotFoundException | IllegalAccessException | NoSuchMethodException ex) {
        log.debug("No endpoint closing sequence of operations was valid");
      }
    }

    GET_OUTPUT_STREAM = getOutputStreamMH;
    CLOSE_OUTPUT = closeOutputMH;
    GET_ASYNC_CONTEXT = getAsyncContextMH;
    COMPLETE = completeMH;
    IS_ASYNC_STARTED = isAsyncStartedMH;
    ABORT = abortMH; // excluded from INITIALIZED
    INITIALIZED =
        getAsyncContextMH != null
            && closeOutputMH != null
            && getAsyncContextMH != null
            && completeMH != null
            && isAsyncStartedMH != null;
  }

  private JettyBlockingHelper() {}

  public static boolean block(
      TraceSegment segment,
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

    request.setAttribute(HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);

    try {
      response.reset();
      response.setStatus(BlockingActionHelper.getHttpCode(statusCode));
      String acceptHeader = request.getHeader("Accept");

      for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
        response.setHeader(e.getKey(), e.getValue());
      }

      if (bct != BlockingContentType.NONE) {
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(bct, acceptHeader);
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-type", BlockingActionHelper.getContentType(type));
        byte[] template = BlockingActionHelper.getTemplate(type);

        if (!response.isWriting()) {
          response.setHeader("Content-length", Integer.toString(template.length));
          OutputStream os = (OutputStream) GET_OUTPUT_STREAM.invoke(response);
          os.write(template);
          os.close();
        } else {
          PrintWriter writer = response.getWriter();
          String respBody = new String(template, StandardCharsets.UTF_8);
          // this might be problematic because the encoding of the writer may not be utf-8
          String respEncoding = response.getCharacterEncoding();
          if ("utf-8".equalsIgnoreCase(respEncoding)) {
            response.setHeader("Content-length", Integer.toString(template.length));
          } // otherwise we don't really know the size after encoding, so don't set the header
          writer.write(respBody);
          writer.close();
        }
      }
      segment.effectivelyBlocked();
      CLOSE_OUTPUT.invoke(response);
      if (ABORT != null) {
        // needed by jetty 9.0.0-9.1.3
        // see https://github.com/eclipse/jetty.project/commit/0d1fca545c0
        ABORT.invoke(request);
      }
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

  public static boolean block(Request request, Response response, Context context) {
    AgentSpan span = spanFromContext(context);
    Flow.Action.RequestBlockingAction rba;
    if (span == null || (rba = span.getRequestBlockingAction()) == null) {
      return false;
    }
    return block(
        span.getRequestContext().getTraceSegment(),
        request,
        response,
        rba.getStatusCode(),
        rba.getBlockingContentType(),
        rba.getExtraHeaders());
  }

  public static boolean hasRequestBlockingAction(Context context) {
    AgentSpan span = spanFromContext(context);
    return span != null && span.getRequestBlockingAction() != null;
  }

  public static void blockAndThrowOnFailure(Request request, Response response, Context context) {
    if (!block(request, response, context)) {
      throw new BlockingException("Throwing after being unable to commit blocking response");
    }
  }
}
