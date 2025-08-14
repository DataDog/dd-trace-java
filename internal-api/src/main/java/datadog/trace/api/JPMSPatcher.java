package datadog.trace.api;

import datadog.environment.JavaVirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JPMSPatcher {
  private static final Logger log = LoggerFactory.getLogger(JPMSPatcher.class);

  public static void patchModules(Instrumentation instrumentation, ClassLoader classLoader) {
    if (JavaVirtualMachine.isJavaVersionAtLeast(9)) {
      try {
        Class<?> target = Class.forName("datadog.trace.util.ModulePatcher");
        Method m = target.getMethod("patchModules", Instrumentation.class, ClassLoader.class);
        m.invoke(null, instrumentation, classLoader);
      } catch (Exception e) {
        // should not happen
        log.error("Unexpected exception while patching modules", e);
      }
    }
  }
}
