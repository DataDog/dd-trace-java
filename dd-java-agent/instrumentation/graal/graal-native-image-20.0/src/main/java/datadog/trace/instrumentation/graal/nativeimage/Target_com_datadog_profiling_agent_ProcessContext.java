package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

@TargetClass(className = "com.datadog.profiling.agent.ProcessContext")
public final class Target_com_datadog_profiling_agent_ProcessContext {
  @Substitute
  public static void register(ConfigProvider configProvider) {
    // This method references com.datadoghq.profiler.OTelContext which is not available
    // during native image build. OTelContext is part of the ddprof native library that's
    // loaded at runtime. For native image builds, profiling context registration is disabled.
    // No-op during native image build.
  }
}
