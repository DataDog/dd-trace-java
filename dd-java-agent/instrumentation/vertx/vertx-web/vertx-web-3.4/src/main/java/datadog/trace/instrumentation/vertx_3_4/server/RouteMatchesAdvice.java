package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper.HANDLER_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper.PARENT_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper.updateRoute;

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
    if (ctx.currentRoute() != null) {
      final String method = ctx.request().rawMethod();
      String path = ctx.currentRoute().getPath();
      String mountPoint = ctx.mountPoint();
      if (mountPoint != null && !mountPoint.isEmpty()) {
        if (mountPoint.charAt(mountPoint.length() - 1) == '/'
            && path != null
            && !path.isEmpty()
            && path.charAt(0) == '/') {
          mountPoint = mountPoint.substring(0, mountPoint.length() - 1);
        }
        path = mountPoint + path;
      }
      updateRoute(ctx, method, path, parentSpan, handlerSpan);
    }
    Map<String, String> params = ctx.pathParams();
    if (params.isEmpty()) {
      return;
    }

    Throwable resThr = PathParameterPublishingHelper.publishParams(params);
    t = resThr;
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
      if (ctx.currentRoute() != null) {
        final String method = ctx.request().rawMethod();
        String path = ctx.currentRoute().getPath();
        String mountPoint = ctx.mountPoint();
        if (mountPoint != null && !mountPoint.isEmpty()) {
          if (mountPoint.charAt(mountPoint.length() - 1) == '/'
              && path != null
              && !path.isEmpty()
              && path.charAt(0) == '/') {
            mountPoint = mountPoint.substring(0, mountPoint.length() - 1);
          }
          path = mountPoint + path;
        }
        updateRoute(ctx, method, path, parentSpan, handlerSpan);
      }
      Map<String, String> params = ctx.pathParams();
      if (params.isEmpty()) {
        return;
      }

      Throwable resThr = PathParameterPublishingHelper.publishParams(params);
      t = resThr;
    }
  }
}
