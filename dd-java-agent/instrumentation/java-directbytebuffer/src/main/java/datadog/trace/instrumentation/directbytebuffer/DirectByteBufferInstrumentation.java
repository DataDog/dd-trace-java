package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;

@AutoService(Instrumenter.class)
public final class DirectByteBufferInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public DirectByteBufferInstrumentation() {
    super("directbytebuffer", "directallocation");
  }

  @Override
  public boolean isEnabled() {
    return Platform.isJavaVersionAtLeast(11) && super.isEnabled() && Platform.hasJfr();
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "java.nio.DirectByteBuffer";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArgument(0, int.class)).and(takesArguments(1)),
        packageName + ".AllocateDirectAdvice");
    transformation.applyAdvice(
        isConstructor()
            .and(takesArgument(0, long.class))
            .and(takesArgument(1, int.class))
            .and(takesArguments(2)),
        packageName + ".NewDirectByteBufferAdvice");
    transformation.applyAdvice(
        isConstructor()
            .and(takesArgument(0, int.class))
            .and(takesArgument(1, long.class))
            .and(takesArgument(2, named("java.io.FileDescriptor")))
            .and(takesArgument(3, named(Runnable.class.getName()))),
        packageName + ".MemoryMappingAdvice");
  }
}
