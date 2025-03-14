package datadog.trace.instrumentation.thrift;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.async.TAsyncMethodCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.thrift.ThriftClientDecorator.CLIENT_DECORATOR;

public class AsyncMethodCallMethodAdvice {
  public static final Logger logger = LoggerFactory.getLogger(AsyncMethodCallMethodAdvice.class);

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.This final TAsyncMethodCall methodCall,
                                   @Advice.AllArguments final Object[] args,
                                   @Advice.FieldValue("callback") final AsyncMethodCallback<Object> callback) {
    AgentSpan agentSpan = CLIENT_DECORATOR.createSpan(methodCall.getClass().getName(), null);
    AgentScope scope = activateSpan(agentSpan);
    try {
      ThriftConstants.setValue(TAsyncMethodCall.class, methodCall, "callback", new DataDogAsyncMethodCallback<Object>(callback, scope));
    } catch (Exception e) {
      if (logger.isDebugEnabled()){
        logger.debug("set value callback fail",e);
      }
      logger.error("set value callback fail",e);
    }
    return scope;
  }
}
