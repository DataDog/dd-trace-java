package datadog.trace.instrumentation.tibcobw5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ThreadPoolInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ThreadPoolInstrumentation() {
    super("tibco");
  }

  @Override
  public String instrumentedType() {
    return "com.tibco.pe.util.ThreadPool";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        NameMatchers.named("schedule"), getClass().getName() + "$ScheduleAdvice");
  }

  public static class ScheduleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
      if (activeSpan() != null && !(runnable instanceof Wrapper)) {
        runnable = Wrapper.wrap(runnable);
      }
    }
  }
}
