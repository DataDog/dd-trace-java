package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.instrumentation.vertx_4_0.server.RouteUpdateHelper.HANDLER_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_4_0.server.RouteUpdateHelper.PARENT_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_4_0.server.RouteUpdateHelper.updateRoute;

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
      final String method = ctx.request().method().name();
      String path = ctx.currentRoute().getPath();
      if (path == null) {
        path = ctx.currentRoute().getName();
      }
      final String mountPoint = ctx.mountPoint();
      if (mountPoint != null && path != null) {
        final String noBackslashhMountPoint =
            mountPoint.endsWith("/")
                ? mountPoint.substring(0, mountPoint.lastIndexOf("/"))
                : mountPoint;
        path = noBackslashhMountPoint + path;
      }
      updateRoute(ctx, method, path, parentSpan, handlerSpan);
    }
    Map<String, String> params = ctx.pathParams();
    if (params.isEmpty()) {
      return;
    }
    if (params.size() == 1 && params.containsKey("*")) {
      // vert.x 5 removes the entry after our advice so we must ignore it
      return;
    }

    Throwable throwable = PathParameterPublishingHelper.publishParams(params);
    t = throwable;
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
        final String method = ctx.request().method().name();
        String path = ctx.currentRoute().getPath();
        if (path == null) {
          path = ctx.currentRoute().getName();
        }
        final String mountPoint = ctx.mountPoint();
        if (mountPoint != null && path != null) {
          final String noBackslashhMountPoint =
              mountPoint.endsWith("/")
                  ? mountPoint.substring(0, mountPoint.lastIndexOf("/"))
                  : mountPoint;
          path = noBackslashhMountPoint + path;
        }
        updateRoute(ctx, method, path, parentSpan, handlerSpan);
      }
      Map<String, String> params = ctx.pathParams();
      if (params.isEmpty()) {
        return;
      }
      if (params.size() == 1 && params.containsKey("*")) {
        // vert.x 5 removes the entry after our advice so we must ignore it
        return;
      }

      Throwable throwable = PathParameterPublishingHelper.publishParams(params);
      t = throwable;
    }
  }
}
