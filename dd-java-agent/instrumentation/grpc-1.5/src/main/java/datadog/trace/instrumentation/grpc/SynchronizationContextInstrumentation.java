package datadog.trace.instrumentation.grpc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.experimental.ProfilingContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class SynchronizationContextInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForSingleType {

  public SynchronizationContextInstrumentation() {
    super("grpc", "grpc-sync-context");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".TimedRunnable"};
  }

  @Override
  public String instrumentedType() {
    return "io.grpc.SynchronizationContext";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(namedOneOf("executeLater", "schedule").and(takesArgument(0, Runnable.class))),
        getClass().getName() + "$Wrap");
  }

  public static final class Wrap {
    @Advice.OnMethodEnter
    public static void executeLater(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      ProfilingContext context = AgentTracer.get().getProfilingContext();
      // config doesn't make it easy enough to enable an instrumentation only when a flag is set,
      // so we'll check here for now and hope the check gets optimised away by the JIT
      if (context instanceof ProfilingContextIntegration
          && ((ProfilingContextIntegration) context).isQueuingTimeEnabled()
          && !(task instanceof TimedRunnable)) {
        // we know it's safe to wrap here (this will be removed at some point and moved into the
        // scope manager)
        task = new TimedRunnable(task);
      }
    }
  }
}
