package datadog.trace.instrumentation.reactor.netty;

import datadog.context.Context;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;

public class AfterConstructorAdvice {
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Thrown Throwable throwable, @Advice.Return(readOnly = false) HttpClient client) {
    if (null == throwable) {
      ContextStore<HttpClientRequest, Context> requestContexts =
          InstrumentationContext.get(HttpClientRequest.class, Context.class);
      client =
          client
              .mapConnect(new CaptureConnectSpan())
              .doOnRequest(new TransferConnectSpan(requestContexts))
              .doOnDisconnected(new ClearRequestContext(requestContexts));
    }
  }
}
