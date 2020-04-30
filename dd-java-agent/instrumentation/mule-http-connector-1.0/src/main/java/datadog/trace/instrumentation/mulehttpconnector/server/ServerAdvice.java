package datadog.trace.instrumentation.mulehttpconnector.server;

import static datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator.onHttpCodecFilterExit;

import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpHeader;

public class ServerAdvice {

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(1) final HttpHeader httpHeader) {
    onHttpCodecFilterExit(ctx, httpHeader);
  }
}
