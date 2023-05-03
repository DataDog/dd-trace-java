package datadog.trace.instrumentation.directbytebuffer;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.Platform;

@AutoService(Instrumenter.class)
public final class DirectByteBufferInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public DirectByteBufferInstrumentation() {
    super("jni", "directallocation");
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
    return "java.nio.DirectByteBuffer";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor()
            .and(takesArgument(0, long.class))
            .and(takesArgument(1, int.class))
            .and(takesArguments(2)),
        packageName + ".NewDirectByteBufferAdvice");
  }
}
