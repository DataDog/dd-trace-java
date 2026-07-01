package datadog.trace.instrumentation.akkahttp106;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.akkahttp106.AkkaHttpClientDecorator.DECORATE;

import akka.http.scaladsl.model.HttpRequest;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import net.bytebuddy.asm.Advice;

@AppliesOn(CONTEXT_TRACKING)
public class SingleRequestContextPropagationAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void methodEnter(
      @Advice.Argument(value = 0, readOnly = false) HttpRequest request) {
    if (request == null) {
      return;
    }
    final AkkaHttpClientHelpers.AkkaHttpHeaders headers =
        new AkkaHttpClientHelpers.AkkaHttpHeaders(request);

    DECORATE.injectContext(getCurrentContext(), request, headers);
    request = headers.getRequest();
  }
}
