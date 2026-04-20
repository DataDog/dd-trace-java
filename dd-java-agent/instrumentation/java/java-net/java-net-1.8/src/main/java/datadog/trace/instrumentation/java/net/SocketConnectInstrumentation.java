package datadog.trace.instrumentation.java.net;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED_DEFAULT;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class SocketConnectInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SocketConnectInstrumentation() {
    super("socket");
  }

  @Override
  public String instrumentedType() {
    return "java.net.Socket";
  }

  @Override
  public boolean isEnabled() {
    // only needed if wallclock profiling is enabled, which requires tracing
    return super.isEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(
                PROFILING_DATADOG_PROFILER_WALL_ENABLED,
                PROFILING_DATADOG_PROFILER_WALL_ENABLED_DEFAULT)
        && InstrumenterConfig.get().isTraceEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("connect"))
            .and(takesArgument(0, named("java.net.SocketAddress")))
            .and(takesArgument(1, int.class)),
        getClass().getName() + "$DisableWallclockSampling");
  }

  public static final class DisableWallclockSampling {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean before() {
      AgentSpan activeSpan = AgentTracer.activeSpan();
      if (activeSpan != null) {
        AgentTracer.get().getProfilingContext().onDetach();
        return true;
      }
      return false;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter boolean wasEnabled) {
      if (wasEnabled) {
        AgentTracer.get().getProfilingContext().onAttach();
      }
    }
  }
}
