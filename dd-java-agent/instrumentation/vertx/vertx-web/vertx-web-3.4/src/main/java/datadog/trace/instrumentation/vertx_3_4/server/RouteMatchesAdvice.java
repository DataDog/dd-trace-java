package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper.HANDLER_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper.PARENT_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper.updateRouteFromContext;

import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;

class RouteMatchesAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  @Source(SourceTypes.REQUEST_PATH_PARAMETER)
  static void after(
      @Advice.Return int ret,
      @Advice.Argument(0) final RoutingContext ctx,
      @Advice.Thrown(readOnly = false) Throwable t) {
    if (ret != 0 || t != null) {
      return;
    }
    final AgentSpan parentSpan = ctx.get(PARENT_SPAN_CONTEXT_KEY);
    final AgentSpan handlerSpan = ctx.get(HANDLER_SPAN_CONTEXT_KEY);
    updateRouteFromContext(ctx, parentSpan, handlerSpan);

    final Map<String, String> params = ctx.pathParams();
    if (params.isEmpty()) {
      return;
    }
    t = PathParameterPublishingHelper.publishParams(params);
  }

  static class BooleanReturnVariant {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    @Source(SourceTypes.REQUEST_PATH_PARAMETER)
    static void after(
        @Advice.Return boolean ret,
        @Advice.Argument(0) final RoutingContext ctx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (!ret || t != null) {
        return;
      }
      final AgentSpan parentSpan = ctx.get(PARENT_SPAN_CONTEXT_KEY);
      final AgentSpan handlerSpan = ctx.get(HANDLER_SPAN_CONTEXT_KEY);
      updateRouteFromContext(ctx, parentSpan, handlerSpan);

      final Map<String, String> params = ctx.pathParams();
      if (params.isEmpty()) {
        return;
      }
      t = PathParameterPublishingHelper.publishParams(params);
    }
  }
}
