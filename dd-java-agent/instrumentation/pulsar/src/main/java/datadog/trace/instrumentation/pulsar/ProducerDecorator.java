package datadog.trace.instrumentation.pulsar;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.pulsar.MessageTextMapSetter.SETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class ProducerDecorator extends BaseDecorator {
  public static final CharSequence ROCKETMQ_NAME = UTF8BytesString.create("pulsar");
   ProducerDecorator(){}
  @Override
  protected String[] instrumentationNames() {
    return new String[]{"pulsar"};
  }

  @Override
  protected CharSequence spanType() {
    return ROCKETMQ_NAME;
  }

  @Override
  protected CharSequence component() {
    return null;
  }

  public AgentScope start(PulsarRequest request){
    UTF8BytesString spanName = UTF8BytesString.create(request.getDestination()+" send");
     final AgentSpan span = startSpan(spanName);
    span.setServiceName("pulsar");
    span.setResourceName(spanName);
    span.setTag("topic",request.getDestination());
    span.setTag("broker_url",request.getUrlData());
    afterStart(span);
    propagate().inject(span,request, SETTER);
    return  activateSpan(span);
  } 

  public void end(AgentSpan scope, PulsarRequest request, Exception e) {
     if (e != null){
       scope.setError(true);
       scope.setErrorMessage(e.getMessage());
     }
     beforeFinish(scope);
     scope.finish();
  }
}
