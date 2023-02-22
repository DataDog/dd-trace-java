package datadog.trace.instrumentation.java.lang;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.IastAdvice.Sink;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.model.VulnerabilityTypes;
import datadog.trace.api.iast.sink.CommandInjectionModule;
import java.util.List;
import javax.annotation.Nullable;

// TODO deal with the environment
@Sink(VulnerabilityTypes.COMMAND_INJECTION)
@CallSite(spi = IastAdvice.class)
public class ProcessBuilderCallSite {

  @CallSite.Before("java.lang.Process java.lang.ProcessBuilder.start()")
  public static void beforeStart(@CallSite.This @Nullable final ProcessBuilder self) {
    if (self == null) {
      return;
    }
    // be careful when fetching the environment as it does mutate the instance
    final List<String> cmd = self.command();
    if (cmd != null && cmd.size() > 0) {
      final CommandInjectionModule module = InstrumentationBridge.COMMAND_INJECTION;
      if (module != null) {
        try {
          module.onProcessBuilderStart(cmd);
        } catch (final Throwable e) {
          module.onUnexpectedException("beforeStart threw", e);
        }
      }
    }
  }
}
