package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.play.appsec.PathExtractionHelpers;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import play.libs.F;

public class ArgumentCaptureWrappers {
  public static class ArgumentCaptureFunction<R> implements Function<Object, R> {
    private final Function<Object, R> delegate;

    public ArgumentCaptureFunction(Function<Object, R> delegate) {
      this.delegate = delegate;
    }

    @Override
    public R apply(Object o) {
      if (o == null) {
        return delegate.apply(null);
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return delegate.apply(o);
      }

      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext.getData(RequestContextSlot.APPSEC) == null) {
        return delegate.apply(o);
      }

      Map<String, Object> conv = Collections.singletonMap("0", o);

      BlockingException t =
          PathExtractionHelpers.callRequestPathParamsCallback(
              requestContext, conv, "RoutingDsl#routeTo");
      if (t != null) {
        throw t;
      }

      return delegate.apply(o);
    }
  }

  public static class ArgumentCaptureBiFunction<R> implements BiFunction<Object, Object, R> {
    private final BiFunction<Object, Object, R> delegate;

    public ArgumentCaptureBiFunction(BiFunction<Object, Object, R> delegate) {
      this.delegate = delegate;
    }

    @Override
    public R apply(Object o1, Object o2) {
      if (o1 == null && o2 == null) {
        return delegate.apply(null, null);
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return delegate.apply(o1, o2);
      }

      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext.getData(RequestContextSlot.APPSEC) == null) {
        return delegate.apply(o1, o2);
      }

      Map<String, Object> conv = new HashMap<>();
      conv.put("0", o1);
      conv.put("1", o2);

      BlockingException t =
          PathExtractionHelpers.callRequestPathParamsCallback(
              requestContext, conv, "RoutingDsl#routeTo");
      if (t != null) {
        throw t;
      }

      return delegate.apply(o1, o2);
    }
  }

  public static class ArgumentCaptureFunction3<R>
      implements F.Function3<Object, Object, Object, R> {
    private final F.Function3<Object, Object, Object, R> delegate;

    public ArgumentCaptureFunction3(F.Function3<Object, Object, Object, R> delegate) {
      this.delegate = delegate;
    }

    @Override
    public R apply(Object o1, Object o2, Object o3) throws Throwable {
      if (o1 == null && o2 == null && o3 == null) {
        return delegate.apply(null, null, null);
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return delegate.apply(o1, o2, o3);
      }

      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext.getData(RequestContextSlot.APPSEC) == null) {
        return delegate.apply(o1, o2, o3);
      }

      Map<String, Object> conv = new HashMap<>();
      conv.put("0", o1);
      conv.put("1", o2);
      conv.put("2", o3);

      BlockingException t =
          PathExtractionHelpers.callRequestPathParamsCallback(
              requestContext, conv, "RoutingDsl#routeTo");
      if (t != null) {
        throw t;
      }

      return delegate.apply(o1, o2, o3);
    }
  }
}
