package datadog.trace.instrumentation.jetty12;

import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;

/**
 * Request-phase AppSec blocking on {@code Server.handle(Request, Response, Callback)}: when the
 * request was flagged for blocking during {@code onRequest}, commits the blocking response and
 * skips the handler chain. Otherwise wraps the Handler callback so that a later mid-handler
 * blocking response write is not cut when the request completes.
 */
public class JettyServerHandleBlockingAdvice {

  @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
  public static boolean /* skip */ onEnter(
      @Advice.Argument(0) final Request request,
      @Advice.Argument(value = 2, readOnly = false) Callback callback) {
    if (JettyBlockResponseHelper.tryBlockRequest(request, callback)) {
      return true;
    }
    callback = JettyBlockResponseHelper.deferCompletion(request, callback);
    return false;
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
