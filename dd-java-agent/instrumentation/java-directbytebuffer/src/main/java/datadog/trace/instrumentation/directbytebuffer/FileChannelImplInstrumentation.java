package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

@AutoService(Instrumenter.class)
public final class FileChannelImplInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public FileChannelImplInstrumentation() {
    super("mmap", "directallocation");
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
    return "sun.nio.ch.FileChannelImpl";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("map"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("java.nio.channels.FileChannel$MapMode")))
            .and(takesArgument(1, long.class))
            .and(takesArgument(2, long.class)),
        packageName + ".MemoryMappingAdvice");
  }
}
