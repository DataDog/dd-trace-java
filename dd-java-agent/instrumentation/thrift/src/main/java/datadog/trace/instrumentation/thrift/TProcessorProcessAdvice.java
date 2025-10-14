package datadog.trace.instrumentation.thrift;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.thrift.ThriftConstants.CONTEXT_THREAD;
import static datadog.trace.instrumentation.thrift.ThriftServerDecorator.SERVER_DECORATOR;

/**
 * @Description @Author lenovo @Date 2022/11/25 11:12
 */
public class TProcessorProcessAdvice {
  public static final Logger logger = LoggerFactory.getLogger(TProcessorProcessAdvice.class);

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.This final Object obj, @Advice.AllArguments final Object[] args) {
    logger.info("TProcessorProcessAdvice : " + obj.getClass().getName());
    try {
      Object in = args[0];
      if (in instanceof ServerInProtocolWrapper) {
        ((ServerInProtocolWrapper) in).initial(new Context(null));
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return activateSpan(noopSpan());
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void after(@Advice.Thrown final Throwable throwable) {
    AgentSpan span = activeSpan();
    if (span != null) {
      SERVER_DECORATOR.onError(span, throwable);
      SERVER_DECORATOR.beforeFinish(span);
      span.finish();
      CONTEXT_THREAD.remove();
    }
  }
}
