package datadog.trace.instrumentation.java.lang;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.java.lang.ProcessImplInstrumentationHelpers;
import java.io.IOException;
import net.bytebuddy.asm.Advice;

class ProcessImplStartAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentSpan startSpan(@Advice.Argument(0) final String[] command) throws IOException {
    if (!ProcessImplInstrumentationHelpers.ONLINE) {
      return null;
    }

    if (command.length == 0) {
      return null;
    }

    final AgentTracer.TracerAPI tracer = AgentTracer.get();
    final AgentSpan span = tracer.startSpan("appsec", "command_execution");
    span.setSpanType("system");
    span.setResourceName(ProcessImplInstrumentationHelpers.determineResource(command));
    span.setTag("component", "subprocess");
    span.context().setIntegrationName("subprocess");
    ProcessImplInstrumentationHelpers.setTags(span, command);
    ProcessImplInstrumentationHelpers.cmdiRaspCheck(command);
    return span;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void endSpan(
      @Advice.Return Process p, @Advice.Enter AgentSpan span, @Advice.Thrown Throwable t) {
    if (span == null) {
      return;
    }
    if (t != null) {
      span.addThrowable(t);
      span.finish();
      return;
    }

    ProcessImplInstrumentationHelpers.addProcessCompletionHook(p, span);
  }
}
