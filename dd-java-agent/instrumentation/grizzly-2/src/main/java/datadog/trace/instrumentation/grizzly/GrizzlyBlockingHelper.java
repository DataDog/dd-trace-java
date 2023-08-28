package datadog.trace.instrumentation.grizzly;

import static datadog.trace.instrumentation.grizzly.GrizzlyDecorator.DECORATE;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Map;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GrizzlyBlockingHelper {
  private static final Logger log = LoggerFactory.getLogger(GrizzlyBlockingHelper.class);
  private static final MethodHandle GET_OUTPUT_STREAM;

  static {
    MethodHandle getOutputStreamMH = null;
    try {
      Method getOutputStream = Response.class.getMethod("getOutputStream");
      getOutputStreamMH = MethodHandles.lookup().unreflect(getOutputStream);
    } catch (IllegalAccessException | NoSuchMethodException | RuntimeException e) {
      log.error("Lookup of getOutputStream failed. Will be unable to commit blocking response", e);
    }
    GET_OUTPUT_STREAM =
        getOutputStreamMH; // return value changed from NIOOutputStream to OutputStream
  }

  private GrizzlyBlockingHelper() {}

  public static boolean block(
      Request request, Response response, Flow.Action.RequestBlockingAction rba, AgentScope scope) {
    return block(
        request,
        response,
        rba.getStatusCode(),
        rba.getBlockingContentType(),
        rba.getExtraHeaders(),
        scope);
  }

  public static boolean block(
      Request request,
      Response response,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders,
      AgentScope scope) {
    if (GET_OUTPUT_STREAM == null) {
      return false;
    }

    try {
      OutputStream os = (OutputStream) GET_OUTPUT_STREAM.invoke(response);
      response.setStatus(BlockingActionHelper.getHttpCode(statusCode));

      for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
        response.setHeader(h.getKey(), h.getValue());
      }

      if (bct != BlockingContentType.NONE) {
        String acceptHeader = request.getHeader("Accept");
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(bct, acceptHeader);
        response.setHeader("Content-type", BlockingActionHelper.getContentType(type));
        byte[] template = BlockingActionHelper.getTemplate(type);
        response.setHeader("Content-length", Integer.toString(template.length));
        os.write(template);
      }
      os.close();
      response.finish();

      scope.span().getRequestContext().getTraceSegment().effectivelyBlocked();
      SpanClosingListener.LISTENER.onAfterService(request);
    } catch (Throwable e) {
      log.info("Error committing blocking response", e);
      final AgentSpan span = scope.span();
      DECORATE.onError(span, e);
      DECORATE.beforeFinish(span);
      span.finish();
      scope.close();
    }

    return true;
  }
}
