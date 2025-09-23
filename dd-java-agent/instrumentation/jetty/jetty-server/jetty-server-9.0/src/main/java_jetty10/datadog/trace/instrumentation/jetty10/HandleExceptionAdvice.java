package datadog.trace.instrumentation.jetty10;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

class HandleExceptionAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  static void enter(@Advice.Argument(0) Throwable t) {
    if (!(t instanceof BlockingException)) {
      return;
    }

    AgentSpan agentSpan = AgentTracer.activeSpan();
    if (agentSpan == null) {
      return;
    }
    JettyDecorator.DECORATE.onError(agentSpan, t);
  }
}
