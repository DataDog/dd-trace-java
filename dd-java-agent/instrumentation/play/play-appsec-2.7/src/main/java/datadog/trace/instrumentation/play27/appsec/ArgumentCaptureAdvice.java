package datadog.trace.instrumentation.play27.appsec;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.play.appsec.PathExtractionHelpers;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Http;
import play.routing.RequestFunctions;

public class ArgumentCaptureAdvice {
  private static Logger log = LoggerFactory.getLogger(ArgumentCaptureAdvice.class);

  public static class RouteConstructorAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before(@Advice.Argument(value = 3, readOnly = false) Object action) {
      if (action instanceof RequestFunctions.Params1) {
        action = new ArgumentCaptureFunctionParam1((RequestFunctions.Params1) action);
      } else if (action instanceof RequestFunctions.Params2) {
        action = new ArgumentCaptureFunctionParam2((RequestFunctions.Params2) action);
      } else if (action instanceof RequestFunctions.Params3) {
        action = new ArgumentCaptureFunctionParam3((RequestFunctions.Params3) action);
      }
    }
  }

  public static class ArgumentCaptureFunctionParam1<R>
      implements RequestFunctions.Params1<Object, R> {
    private final RequestFunctions.Params1<Object, R> delegate;

    public ArgumentCaptureFunctionParam1(RequestFunctions.Params1<Object, R> delegate) {
      this.delegate = delegate;
    }

    @Override
    public R apply(Http.Request req, Object o1) {
      if (o1 == null) {
        return delegate.apply(req, null);
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return delegate.apply(req, o1);
      }

      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext.getData(RequestContextSlot.APPSEC) == null) {
        return delegate.apply(req, o1);
      }

      Map<String, Object> conv = Collections.singletonMap("0", o1);

      BlockingException t =
          PathExtractionHelpers.callRequestPathParamsCallback(
              requestContext, conv, "RoutingDsl#routingTo");
      if (t != null) {
        throw t;
      }

      return delegate.apply(req, o1);
    }
  }

  public static class ArgumentCaptureFunctionParam2<R>
      implements RequestFunctions.Params2<Object, Object, R> {
    private final RequestFunctions.Params2<Object, Object, R> delegate;

    public ArgumentCaptureFunctionParam2(RequestFunctions.Params2<Object, Object, R> delegate) {
      this.delegate = delegate;
    }

    @Override
    public R apply(Http.Request req, Object o1, Object o2) throws Throwable {
      if (o1 == null && o2 == null) {
        return delegate.apply(req, null, null);
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return delegate.apply(req, o1, o2);
      }

      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext.getData(RequestContextSlot.APPSEC) == null) {
        return delegate.apply(req, o1, o2);
      }

      Map<String, Object> conv = new HashMap<>();
      conv.put("0", o1);
      conv.put("1", o2);

      BlockingException t =
          PathExtractionHelpers.callRequestPathParamsCallback(
              requestContext, conv, "RoutingDsl#routingTo");
      if (t != null) {
        throw t;
      }

      return delegate.apply(req, o1, o2);
    }
  }

  public static class ArgumentCaptureFunctionParam3<R>
      implements RequestFunctions.Params3<Object, Object, Object, R> {
    private final RequestFunctions.Params3<Object, Object, Object, R> delegate;

    public ArgumentCaptureFunctionParam3(
        RequestFunctions.Params3<Object, Object, Object, R> delegate) {
      this.delegate = delegate;
    }

    @Override
    public R apply(Http.Request req, Object o1, Object o2, Object o3) throws Throwable {
      if (o1 == null && o2 == null && o3 == null) {
        return delegate.apply(req, null, null, null);
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return delegate.apply(req, o1, o2, o3);
      }

      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext.getData(RequestContextSlot.APPSEC) == null) {
        return delegate.apply(req, o1, o2, o3);
      }

      Map<String, Object> conv = new HashMap<>();
      conv.put("0", o1);
      conv.put("1", o2);
      conv.put("2", o3);

      BlockingException t =
          PathExtractionHelpers.callRequestPathParamsCallback(
              requestContext, conv, "RoutingDsl#routingTo");
      if (t != null) {
        throw t;
      }

      return delegate.apply(req, o1, o2, o3);
    }
  }
}
