package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.Platform;

@AutoService(Instrumenter.class)
public final class ByteBufferInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public ByteBufferInstrumentation() {
    super("allocatedirect", "directallocation");
  }

  @Override
  public boolean isEnabled() {
    return Platform.isJavaVersionAtLeast(11) && super.isEnabled() && Platform.hasJfr();
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isDirectAllocationProfilingEnabled();
  }

  @Override
  public String instrumentedType() {
    return "java.nio.ByteBuffer";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("allocateDirect"))
            .and(isStatic())
            .and(takesArgument(0, int.class))
            .and(takesArguments(1)),
        packageName + ".AllocateDirectAdvice");
  }
}
