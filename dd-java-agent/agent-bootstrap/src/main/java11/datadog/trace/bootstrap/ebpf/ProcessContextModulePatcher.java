package datadog.trace.bootstrap.ebpf;

import datadog.environment.OperatingSystem;
import datadog.trace.util.ModulePatcher;

// Registered via META-INF/services
public final class ProcessContextModulePatcher implements ModulePatcher.Impl {
  @Override
  public ModulePatcher.ModulePatch patchModule() {
    if (OperatingSystem.isLinux()) {
      // the process context will be generated only on Linux
      return new ModulePatcher.ModulePatch(ProcessContext.class, "java.base")
          .addOpen("java.nio", ModulePatcher.SELF_MODULE_NAME);
    }
    return null;
  }
}
