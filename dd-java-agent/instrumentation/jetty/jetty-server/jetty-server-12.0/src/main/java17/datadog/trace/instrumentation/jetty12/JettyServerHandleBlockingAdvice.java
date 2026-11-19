package datadog.trace.instrumentation.jetty12;

import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;

/**
 * Request-phase AppSec blocking on {@code Server.handle(Request, Response, Callback)}: when the
 * request was flagged for blocking during {@code onRequest}, commits the blocking response and
 * skips the handler chain. Otherwise wraps the Handler callback so that a later mid-handler
 * blocking response write is not cut when the request completes, including when a core handler lets
 * a {@code Throwable} escape {@code Server.handle} while that write is in flight (any other
 * escaping {@code Throwable} propagates unchanged) or returns {@code false} after initiating it.
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

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter final boolean blocked,
      @Advice.Argument(0) final Request request,
      @Advice.Argument(2) final Callback callback,
      @Advice.Return(readOnly = false) boolean handled,
      @Advice.Thrown(readOnly = false) Throwable thrown) {
    if (blocked) {
      // a false return makes Jetty write a 404 on top of the blocking response
      handled = true;
    } else if (thrown != null && JettyBlockResponseHelper.failDeferred(callback, thrown)) {
      // a block write is in flight and Jetty would fail its own reference to the Handler callback,
      // cutting it: fail it through the wrapper instead and return as handled
      thrown = null;
      handled = true;
    } else if (thrown == null
        && !handled
        && JettyBlockResponseHelper.completeUnhandledBlock(request, callback)) {
      // Jetty would write a 404 with its own reference to the Handler callback, over the blocking
      // response: the wrapper was completed instead, so the request can be reported as handled
      handled = true;
    }
  }
}
