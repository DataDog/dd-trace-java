package datadog.trace.instrumentation.vertx_4_0.server;

import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
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
