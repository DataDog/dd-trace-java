package datadog.trace.instrumentation.java.concurrent.virtualthread;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ContinuationInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {
  public ContinuationInstrumentation() {
    super("java_concurrent", "virtual-thread-continuation");
  }

  @Override
  public String instrumentedType() {
    return "jdk.internal.vm.Continuation";
  }

  @Override
  public boolean isEnabled() {
    return Platform.isJavaVersionAtLeast(19) && super.isEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(takesArguments(0)).and(isPrivate()).and(named("mount")),
        getClass().getName() + "$Mount");
    transformer.applyAdvice(
        isMethod().and(takesArguments(0)).and(isPrivate()).and(named("unmount")),
        getClass().getName() + "$Unmount");
  }

  public static final class Mount {
    @Advice.OnMethodEnter
    public static void mount() {
      AgentScope scope = AgentTracer.activeScope();
      if (scope != null && scope.span().context() instanceof ProfilerContext) {
        AgentTracer.get()
            .getProfilingContext()
            .setContext((ProfilerContext) scope.span().context());
      }
    }
  }

  public static final class Unmount {
    @Advice.OnMethodExit
    public static void unmount() {
      AgentTracer.get().getProfilingContext().clearContext();
    }
  }
}
