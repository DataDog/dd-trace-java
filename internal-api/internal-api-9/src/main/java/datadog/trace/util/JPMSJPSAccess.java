package datadog.trace.util;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.trace.api.Platform;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JPMSJPSAccess {
  private static Logger log = LoggerFactory.getLogger(JPMSJPSAccess.class);

  public static void patchModuleAccess(Instrumentation inst) {
    Module unnamedModule = ClassLoader.getSystemClassLoader().getUnnamedModule();
    Module jvmstatModule = ModuleLayer.boot().findModule("jdk.internal.jvmstat").orElse(null);

    if (jvmstatModule != null) {
      Map<String, Set<Module>> extraOpens = Map.of("sun.jvmstat.monitor", Set.of(unnamedModule));

      // Redefine the module
      inst.redefineModule(
          jvmstatModule,
          Collections.emptySet(),
          extraOpens,
          extraOpens,
          Collections.emptySet(),
          Collections.emptyMap());
    } else {
      log.debug(
          SEND_TELEMETRY,
          "Failed to find the jdk.internal.jvmstat module, skipping patching of module access on "
              + Platform.getRuntimeVersion()
              + " "
              + Platform.getRuntimeVendor());
    }
  }
}
