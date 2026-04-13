package datadog.trace.instrumentation.directbytebuffer;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

@AutoService(InstrumenterModule.class)
public final class DirectByteBufferInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public DirectByteBufferInstrumentation() {
    super("jni", "directallocation");
  }

  @Override
  public boolean isEnabled() {
    ConfigProvider cp = ConfigProvider.getInstance();
    return JavaVirtualMachine.isJavaVersionAtLeast(11)
        && super.isEnabled()
        && DirectMemoryProfilingHelper.isEnabled(cp)
        && Platform.hasJfr();
  }

  @Override
  public String instrumentedType() {
    return "java.nio.DirectByteBuffer";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(0, long.class))
            .and(takesArgument(1, int.class))
            .and(takesArguments(2)),
        packageName + ".NewDirectByteBufferAdvice");
  }
}
