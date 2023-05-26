package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator.onHttpServerFilterPrepareResponseEnter;
import static datadog.trace.instrumentation.grizzlyhttp232.GrizzlyDecorator.onHttpServerFilterPrepareResponseExit;

import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.HttpStatus;

public class HttpServerFilterAdvice {

  @Advice.OnMethodEnter
  public static boolean onEnter(
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(2) final HttpResponsePacket response) {
    if (response.getHttpStatus() == HttpStatus.CONINTUE_100) {
      return true;
    }
    onHttpServerFilterPrepareResponseEnter(ctx, response);
    return false;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter boolean isContinue,
      @Advice.Argument(0) final FilterChainContext ctx,
      @Advice.Argument(2) final HttpResponsePacket response) {
    if (!isContinue) {
      onHttpServerFilterPrepareResponseExit(ctx, response);
    }
  }
}
