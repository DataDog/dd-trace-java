package datadog.trace.instrumentation.jetty93;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty9.JettyDecorator.DECORATE;

import datadog.context.Context;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jetty9.ExtractAdapter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.http.MetaData;
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
      MetaData.Response metaDataResponse /* nullable */,
      HttpTransport transport,
      AtomicBoolean _committed,
      Callback cb) {
    if (metaDataResponse == null) {
      metaDataResponse = newMetaDataResponse(connection.getResponse());
      if (metaDataResponse == null) {
        return false;
      }
    }

    if (metaDataResponse.getStatus() == 100) {
      return false;
    }
    boolean wasCommitted = !_committed.compareAndSet(false, true);
    if (wasCommitted) {
      return false;
    }

    // henceforth we need to reset _committed to false when we don't want to skip the body

    Request req = connection.getRequest();

    Object contextObj;
    Context context;
    AgentSpan span;
    RequestContext requestContext;
    if (req.getAttribute(DD_IGNORE_COMMIT_ATTRIBUTE) != null
        || !((contextObj = req.getAttribute(DD_CONTEXT_ATTRIBUTE)) instanceof Context)
        || (span = spanFromContext(context = (Context) contextObj)) == null
        || (requestContext = span.getRequestContext()) == null) {
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

  private static MetaData.Response newMetaDataResponse(Response resp) {
    Method newMetaDataResponse;
    try {
      newMetaDataResponse = Response.class.getDeclaredMethod("newResponseMetaData");
      newMetaDataResponse.setAccessible(true);
      return (MetaData.Response) newMetaDataResponse.invoke(resp);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      log.debug("Failed creating new MetaData.Response", e);
      return null;
    }
  }
}
