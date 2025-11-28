package datadog.trace.instrumentation.tomcat;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
      TraceSegment segment, Request request, Response resp, Flow.Action.RequestBlockingAction rba) {
    commitBlockingResponse(
        segment,
        request,
        resp,
        rba.getStatusCode(),
        rba.getBlockingContentType(),
        rba.getExtraHeaders(),
        rba.getSecurityResponseId());
  }

  public static boolean commitBlockingResponse(
      TraceSegment segment,
      Request request,
      Response resp,
      int statusCode,
      BlockingContentType templateType,
      Map<String, String> extraHeaders) {
    return commitBlockingResponse(
        segment, request, resp, statusCode, templateType, extraHeaders, null);
  }

  public static boolean commitBlockingResponse(
      TraceSegment segment,
      Request request,
      Response resp,
      int statusCode,
      BlockingContentType templateType,
      Map<String, String> extraHeaders,
      String securityResponseId) {
    if (GET_OUTPUT_STREAM == null) {
      return false;
    }
    int httpCode = BlockingActionHelper.getHttpCode(statusCode);
    if (!start(resp, httpCode)) {
      return true;
    }

    // tomcat, if it sees an exception when dispatching, may set the status code to 500
    // on the response, even if the response has already been committed
    request.setAttribute(TomcatDecorator.DD_REAL_STATUS_CODE, httpCode);

    for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
      resp.setHeader(h.getKey(), h.getValue());
    }

    try {
      try {
        tryWriteWithOutputStream(request, resp, templateType, securityResponseId);
      } catch (IllegalStateException ise) {
        tryWriteWithWriter(request, resp, templateType, securityResponseId);
      }
      segment.effectivelyBlocked();
    } catch (Throwable e) {
      log.info("Error sending error page", e);
    }
    return true;
  }

  private static void tryWriteWithOutputStream(
      Request request, Response resp, BlockingContentType templateType, String securityResponseId)
      throws Throwable {
    OutputStream os = (OutputStream) GET_OUTPUT_STREAM.invoke(resp);
    if (templateType != BlockingContentType.NONE) {
      TemplateType type =
          BlockingActionHelper.determineTemplateType(templateType, request.getHeader("Accept"));
      byte[] template = BlockingActionHelper.getTemplate(type, securityResponseId);

      resp.setHeader("Content-length", Integer.toString(template.length));
      resp.setHeader("Content-type", BlockingActionHelper.getContentType(type));
      os.write(template);
    }
    os.close();
  }

  private static void tryWriteWithWriter(
      Request request, Response resp, BlockingContentType templateType, String securityResponseId)
      throws IOException {
    PrintWriter writer = resp.getWriter();
    if (templateType != BlockingContentType.NONE) {
      TemplateType type =
          BlockingActionHelper.determineTemplateType(templateType, request.getHeader("Accept"));
      byte[] template = BlockingActionHelper.getTemplate(type, securityResponseId);
      String templateStr = new String(template, StandardCharsets.UTF_8);

      resp.setHeader("Content-length", Integer.toString(template.length));
      if ("utf-8".equalsIgnoreCase(resp.getCharacterEncoding())) {
        resp.setHeader("Content-length", Integer.toString(template.length));
      } // otherwise we don't really know the size after encoding, so don't set the header
      resp.setHeader("Content-type", BlockingActionHelper.getContentType(type));
      writer.write(templateStr);
    }
    writer.close();
  }

  private static boolean start(Response resp, int statusCode) {
    if (resp.isCommitted()) {
      log.warn("response already committed, we can't change it");
      return false;
    }

    log.debug("Committing blocking response");

    resp.reset();
    resp.getRequest().setAttribute(HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);
    resp.setStatus(statusCode);

    return true;
  }
}
