package mule4;

import net.bytebuddy.asm.Advice;
import org.mule.runtime.api.util.HttpServerTestBridge;

// Advice to call the bridge code that interacts with the test code
public class HttpServerTestHandlerAdvice {
  @Advice.OnMethodExit()
  static void onExit(
      @Advice.Argument(value = 0) String requestPath,
      @Advice.Return(readOnly = false) Object[] ret) {
    ret = HttpServerTestBridge.testHandle(requestPath);
  }
}
