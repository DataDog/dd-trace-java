package datadog.trace.instrumentation.jetty904;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty9.JettyDecorator.DECORATE;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jetty9.ExtractAdapter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyCommitResponseHelper {
  private static final Logger log = LoggerFactory.getLogger(JettyCommitResponseHelper.class);

  public static boolean /* skip */ before(
      HttpChannel connection,
      HttpGenerator.ResponseInfo responseInfo /* nullable */,
      HttpTransport transport,
      AtomicBoolean _committed,
      Callback cb) {
    if (responseInfo == null) {
      responseInfo = newResponseInfo(connection);
      if (responseInfo == null) {
        return false;
      }
    }

    if (responseInfo.getStatus() == 100) {
      return false;
    }
    boolean wasCommitted = !_committed.compareAndSet(false, true);
    if (wasCommitted) {
      return false;
    }

    // henceforth we need to reset _committed to false when we don't want to skip the body

    Request req = connection.getRequest();

    if (req.getAttribute(DD_IGNORE_COMMIT_ATTRIBUTE) != null) {
      _committed.set(false);
      return false;
    }

    Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
    if (!(existingSpan instanceof AgentSpan)) {
      _committed.set(false);
      return false;
    }
    AgentSpan span = (AgentSpan) existingSpan;
    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null) {
      _committed.set(false);
      return false;
    }

    Response resp = connection.getResponse();

    Flow<Void> flow =
        DECORATE.callIGCallbackResponseAndHeaders(
            span, resp, resp.getStatus(), ExtractAdapter.Response.GETTER);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      _committed.set(false);
      boolean success = JettyOnCommitBlockingHelper.block(connection, transport, rba, cb);
      if (success) {
        _committed.set(true);
        requestContext.getTraceSegment().effectivelyBlocked();
        return true;
      }
    }

    _committed.set(false);
    return false;
  }

  private static HttpGenerator.ResponseInfo newResponseInfo(HttpChannel connection) {
    Response response = connection.getResponse();
    Method newResponseInfo;
    try {
      newResponseInfo = Response.class.getDeclaredMethod("newResponseInfo");
      newResponseInfo.setAccessible(true);
      return (HttpGenerator.ResponseInfo) newResponseInfo.invoke(response);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      log.debug("Failed creating new ResponseInfo", e);
      return null;
    }
  }
}
