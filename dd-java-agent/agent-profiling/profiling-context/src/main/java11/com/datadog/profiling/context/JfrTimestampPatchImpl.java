package com.datadog.profiling.context;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import jdk.jfr.FlightRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JfrTimestampPatchImpl {
  private static final Logger log = LoggerFactory.getLogger(JfrTimestampPatchImpl.class);

  static void execute(ClassLoader agentClassLoader) {
    try {
      Class<?> installerClass = agentClassLoader.loadClass("datadog.trace.agent.tooling.Utils");
      Instrumentation instrumentation =
          (Instrumentation) installerClass.getMethod("getInstrumentation").invoke(null);
      Set<Module> myModules = Collections.singleton(JfrTimestampPatchImpl.class.getModule());
      Module module = FlightRecorder.class.getModule();
      instrumentation.redefineModule(
          module,
          Collections.emptySet(),
          Map.of("jdk.jfr.internal", myModules),
          Map.of("jdk.jfr.internal", myModules),
          Collections.emptySet(),
          Collections.emptyMap());
    } catch (Throwable t) {
      log.debug("Failed to patch the access to JFR timestamp", t);
    }
  }
}
