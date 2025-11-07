package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator.onFilterChainFail;

import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;

public class DefaultFilterChainAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onFail(
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(1) final Throwable throwable) {
    onFilterChainFail(ctx, throwable);
  }
}
