package datadog.trace.instrumentation.vertx_3_4.server;

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
      Map<String, String> params = ctx.pathParams();
      if (params.isEmpty()) {
        return;
      }

      Throwable resThr = PathParameterPublishingHelper.publishParams(params);
      t = resThr;
    }
  }
}
