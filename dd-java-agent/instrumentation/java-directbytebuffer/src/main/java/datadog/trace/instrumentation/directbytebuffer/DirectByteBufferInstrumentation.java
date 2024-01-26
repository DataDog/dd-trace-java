package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

@AutoService(Instrumenter.class)
public final class DirectByteBufferInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public DirectByteBufferInstrumentation() {
    super("jni", "directallocation");
  }

  @Override
  public boolean isEnabled() {
    return Platform.isJavaVersionAtLeast(11)
        && super.isEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(
                PROFILING_DIRECT_ALLOCATION_ENABLED, PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT)
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
