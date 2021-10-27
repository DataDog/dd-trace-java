package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator.onHttpServerFilterPrepareResponseEnter;
import static datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator.onHttpServerFilterPrepareResponseExit;

import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpResponsePacket;

public class HttpServerFilterAdvice {

  @Advice.OnMethodEnter
  public static void onEnter(
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(2) final HttpResponsePacket response) {
    onHttpServerFilterPrepareResponseEnter(ctx, response);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(2) final HttpResponsePacket response) {
    onHttpServerFilterPrepareResponseExit(ctx, response);
  }
}
