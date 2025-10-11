package datadog.trace.instrumentation.ratpack;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;
import ratpack.handling.Handler;
import ratpack.handling.internal.ChainHandler;
import ratpack.path.PathBinder;
import ratpack.path.internal.TokenPathBinder;

/**
 * @see ratpack.path.internal.PathBindingStorage
 */
public class PathHandlerAdvice {

  // limitation: we can publish tokens for more than one handler in the same request
  // only the first one will be considered further down
  @Advice.OnMethodEnter(suppress = Throwable.class)
  @SuppressFBWarnings(
      value = "UC_USELESS_OBJECT",
      justification = "write to handler is not without effect")
  static void before(
      @Advice.Argument(0) PathBinder pathBinder,
      @Advice.Argument(value = 1, readOnly = false) Handler handler) {
    if (!(pathBinder instanceof TokenPathBinder)) {
      return;
    }

    if (!TokenPathBinderInspector.hasTokenNames((TokenPathBinder) pathBinder)) {
      return;
    }

    if (handler instanceof ChainHandler) {
      Handler[] unpacked = ChainHandler.unpack(handler);
      Handler[] withPubHandler = new Handler[unpacked.length + 1];
      System.arraycopy(unpacked, 0, withPubHandler, 1, unpacked.length);
      withPubHandler[0] = PathBindingPublishingHandler.INSTANCE;
      handler = new ChainHandler(withPubHandler);
    } else {
      handler = new ChainHandler(PathBindingPublishingHandler.INSTANCE, handler);
    }
  }
}
