package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

@AutoService(InstrumenterModule.class)
public final class ByteBufferInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ByteBufferInstrumentation() {
    super("allocatedirect", "directallocation");
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(11)
        && super.isEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(
                PROFILING_DIRECT_ALLOCATION_ENABLED, PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT)
        && Platform.hasJfr();
  }

  @Override
  public String instrumentedType() {
    return "java.nio.ByteBuffer";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("allocateDirect"))
            .and(isStatic())
            .and(takesArgument(0, int.class))
            .and(takesArguments(1)),
        packageName + ".AllocateDirectAdvice");
  }
}
