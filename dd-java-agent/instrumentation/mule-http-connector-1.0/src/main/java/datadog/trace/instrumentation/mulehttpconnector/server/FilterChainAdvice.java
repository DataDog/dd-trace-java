package datadog.trace.instrumentation.mulehttpconnector.server;

import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.onFilterChainFail;

import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;

public class FilterChainAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onFail(
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(1) final Throwable throwable) {
    onFilterChainFail(ctx, throwable);
  }
}
