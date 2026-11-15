package datadog.trace.instrumentation.jetty12;

import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;

/**
 * Request-phase AppSec blocking on {@code Server.handle(Request, Response, Callback)}: when the
 * request was flagged for blocking during {@code onRequest}, commits the blocking response and
 * skips the handler chain.
 */
public class JettyServerHandleBlockingAdvice {

  @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
  public static boolean /* skip */ onEnter(
      @Advice.Argument(0) final Request request, @Advice.Argument(2) final Callback callback) {
    return JettyBlockResponseHelper.tryBlockRequest(request, callback);
  }

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final boolean blocked, @Advice.Return(readOnly = false) boolean handled) {
    if (blocked) {
      // a false return makes Jetty write a 404 on top of the blocking response
      handled = true;
    }
  }
}
