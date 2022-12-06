package datadog.trace.instrumentation.reactor.netty;

import net.bytebuddy.asm.Advice;
import reactor.netty.http.client.HttpClient;

public class AfterConstructorAdvice {
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Thrown Throwable throwable, @Advice.Return(readOnly = false) HttpClient client) {
    if (null == throwable) {
      client = client.mapConnect(new CaptureConnectSpan()).doOnRequest(new TransferConnectSpan());
    }
  }
}
