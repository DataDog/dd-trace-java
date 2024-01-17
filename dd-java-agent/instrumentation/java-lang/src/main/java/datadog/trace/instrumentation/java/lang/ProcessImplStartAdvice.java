package datadog.trace.instrumentation.java.lang;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.java.lang.ProcessImplInstrumentationHelpers;
import java.io.IOException;
import java.util.Map;
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

    // Don't create spans for agent threads
    if (AGENT_THREAD_GROUP.equals(Thread.currentThread().getThreadGroup())) {
      return null;
    }

    AgentTracer.TracerAPI tracer = AgentTracer.get();

    Map<String, String> tags = ProcessImplInstrumentationHelpers.createTags(command);
    TagContext tagContext = new TagContext("appsec", tags);
    AgentSpan span = tracer.startSpan("appsec", "command_execution", tagContext);
    span.setSpanType("system");
    span.setResourceName(ProcessImplInstrumentationHelpers.determineResource(command));
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
