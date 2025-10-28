package datadog.trace.instrumentation.jetty9421;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty9.JettyDecorator.DECORATE;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jetty9.ExtractAdapter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpChannelState;
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
      Callback cb) {

    HttpChannelState state = connection.getState();
    boolean committing = state.commitResponse();
    if (!committing) {
      return false;
    }
    // henceforth we need to reset the state

    if (metaDataResponse == null) {
      metaDataResponse = newMetaDataResponse(connection.getResponse());
      if (metaDataResponse == null) {
        state.partialResponse();
        return false;
      }
    }

    if (metaDataResponse.getStatus() >= 100 && metaDataResponse.getStatus() < 200) {
      state.partialResponse();
      return false;
    }

    Request req = connection.getRequest();

    if (req.getAttribute(DD_IGNORE_COMMIT_ATTRIBUTE) != null) {
      state.partialResponse();
      return false;
    }

    Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
    if (!(existingSpan instanceof AgentSpan)) {
      state.partialResponse();
      return false;
    }
    AgentSpan span = (AgentSpan) existingSpan;
    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null) {
      state.partialResponse();
      return false;
    }

    Response resp = connection.getResponse();
    Flow<Void> flow =
        DECORATE.callIGCallbackResponseAndHeaders(
            span, resp, resp.getStatus(), ExtractAdapter.Response.GETTER);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      boolean success = JettyOnCommitBlockingHelper.block(connection, transport, rba, cb);
      if (success) {
        requestContext.getTraceSegment().effectivelyBlocked();
        return true;
      }
    }

    state.partialResponse();
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
