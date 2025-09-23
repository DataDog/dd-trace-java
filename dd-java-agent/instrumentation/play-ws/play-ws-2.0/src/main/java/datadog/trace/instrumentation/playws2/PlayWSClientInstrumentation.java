package datadog.trace.instrumentation.playws2;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.playws.HeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.playws.PlayWSClientDecorator.DECORATE;
import static datadog.trace.instrumentation.playws.PlayWSClientDecorator.PLAY_WS_REQUEST;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.playws.BasePlayWSClientInstrumentation;
import net.bytebuddy.asm.Advice;
import play.shaded.ahc.org.asynchttpclient.AsyncHandler;
import play.shaded.ahc.org.asynchttpclient.Request;
import play.shaded.ahc.org.asynchttpclient.handler.StreamedAsyncHandler;
import play.shaded.ahc.org.asynchttpclient.ws.WebSocketUpgradeHandler;

@AutoService(InstrumenterModule.class)
public class PlayWSClientInstrumentation extends BasePlayWSClientInstrumentation {
  public static class ClientAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan methodEnter(
        @Advice.Argument(0) final Request request,
        @Advice.Argument(value = 1, readOnly = false) AsyncHandler asyncHandler) {

      final AgentSpan span = startSpan("play-ws", PLAY_WS_REQUEST);

      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      DECORATE.injectContext(getCurrentContext().with(span), request, SETTER);

      if (asyncHandler instanceof StreamedAsyncHandler) {
        asyncHandler = new StreamedAsyncHandlerWrapper((StreamedAsyncHandler) asyncHandler, span);
      } else if (!(asyncHandler instanceof WebSocketUpgradeHandler)) {
        // websocket upgrade handlers aren't supported
        asyncHandler = new AsyncHandlerWrapper(asyncHandler, span);
      }

      return span;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentSpan clientSpan, @Advice.Thrown final Throwable throwable) {

      if (throwable != null) {
        DECORATE.onError(clientSpan, throwable);
        DECORATE.beforeFinish(clientSpan);
        clientSpan.finish();
      }
    }
  }
}
