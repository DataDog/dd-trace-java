package datadog.trace.instrumentation.mulehttpconnector;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.mule.runtime.http.api.domain.message.response.HttpResponse;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.MuleHttpConnectorDecorator.DECORATE;

public class HttpListenerResponseAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void stopSpan(
      @Advice.This final Object source,
      @Advice.FieldValue("httpResponse") final HttpResponse httpResponse) {
    final AgentSpan span = startSpan("mule.http.connector.request");
    DECORATE.afterStart(span);
    DECORATE.onResponse(span, httpResponse);

    DECORATE.beforeFinish(span);
    span.finish();
  }
}
