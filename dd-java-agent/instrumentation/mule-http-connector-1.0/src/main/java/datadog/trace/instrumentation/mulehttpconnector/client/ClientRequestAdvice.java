package datadog.trace.instrumentation.mulehttpconnector.client;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Request;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.mule.service.http.impl.service.client.async.ResponseAsyncHandler;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.client.ClientDecorator.DECORATE;

// import static
// datadog.trace.instrumentation.mulehttpconnector.HttpRequesterResponseInjectAdapter.SETTER;

public class ClientRequestAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onEnter(
      @Advice.This final Object source,
      @Advice.Argument(0) final Request request,
      @Advice.Argument(1) final AsyncHandler<?> handler) {

    if (handler instanceof ResponseAsyncHandler) {
      final AgentSpan span = startSpan("mule.http.requester.request");
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      final String resourceName = request.getMethod() + " " + source.getClass().getName();
      span.setTag(DDTags.RESOURCE_NAME, resourceName);
      //      propagate().inject(span, request.getHeaders(), SETTER);
      InstrumentationContext.get(ResponseAsyncHandler.class, AgentSpan.class)
          .put((ResponseAsyncHandler) handler, span);
    }
  }
}
