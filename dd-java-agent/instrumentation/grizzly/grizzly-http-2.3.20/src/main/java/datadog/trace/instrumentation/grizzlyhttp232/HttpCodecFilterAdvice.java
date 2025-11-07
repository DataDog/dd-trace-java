package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator.onHttpCodecFilterExit;

import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpCodecFilter;
import org.glassfish.grizzly.http.HttpHeader;

public class HttpCodecFilterAdvice {

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(1) final HttpHeader httpHeader,
      @Advice.This HttpCodecFilter thiz,
      @Advice.Return(readOnly = false) NextAction nextAction) {
    nextAction = onHttpCodecFilterExit(ctx, httpHeader, thiz, nextAction);
  }
}
