package datadog.trace.instrumentation.mulehttpconnector;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Request;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.mule.service.http.impl.service.client.async.ResponseAsyncHandler;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.MuleHttpConnectorDecorator.DECORATE;

public class HttpRequesterRequestAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onEnter(
      @Advice.This final Object source,
      @Advice.Argument(0) final Request request,
      @Advice.Argument(1) final AsyncHandler<?> handler) {

    // TO-DO: check if AsyncHandler needs to be raw type (AsyncHandler<?>) or if you can implicitly
    // convert to ResponseAsyncHandler
    if (handler instanceof ResponseAsyncHandler) {
      final AgentSpan span = startSpan("mule.http.requester.request");
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      final String resourceName = request.getMethod() + " " + source.getClass().getName();
      span.setTag(DDTags.RESOURCE_NAME, resourceName);

      //      final AgentScope scope = activateSpan(span, false);
      //      scope.setAsyncPropagation(true);
      InstrumentationContext.get(ResponseAsyncHandler.class, AgentSpan.class)
          .put((ResponseAsyncHandler) handler, span);
    }
  }
}
