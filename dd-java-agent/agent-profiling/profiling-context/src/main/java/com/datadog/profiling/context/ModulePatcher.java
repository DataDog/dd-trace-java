package com.datadog.profiling.context;

import java.lang.instrument.Instrumentation;

public final class ModulePatcher {
  public static void execute(Instrumentation inst) {
    ModulePatcherImpl.execute(inst);
  }
}
