package com.datadog.profiling.context;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import jdk.jfr.FlightRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ModulePatcherImpl {
  private static final Logger log = LoggerFactory.getLogger(ModulePatcherImpl.class);

  static void execute(Instrumentation inst) {
    Set<Module> myModules = Collections.singleton(ModulePatcherImpl.class.getModule());

    patchJfrTimestamp(myModules, inst);
  }

  private static void patchJfrTimestamp(Set<Module> myModules, Instrumentation inst) {
    try {
      Module target = FlightRecorder.class.getModule();
      inst.redefineModule(
          target,
          Collections.emptySet(),
          Map.of("jdk.jfr.internal", myModules),
          Map.of("jdk.jfr.internal", myModules),
          Collections.emptySet(),
          Collections.emptyMap());
    } catch (Throwable t) {
      log.warn("Failed patching module system for accessing JFR timestamp", t);
    }
  }
}
