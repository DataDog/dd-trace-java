package datadog.trace.util;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class JPMSJPSAccess {
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
    }
  }
}
