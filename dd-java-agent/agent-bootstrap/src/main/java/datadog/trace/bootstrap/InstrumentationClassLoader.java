package datadog.trace.bootstrap;

import java.security.CodeSource;
import java.security.SecureClassLoader;

/** Holds Muzzle and Instrumentation classes, so they can be unloaded separately to the agent. */
final class InstrumentationClassLoader extends SecureClassLoader {
  static {
    ClassLoader.registerAsParallelCapable();
  }

  InstrumentationClassLoader(DatadogClassLoader parent) {
    super(parent);
  }

  Class<?> loadInstrumentationClass(String name, CodeSource agentCodeSource)
      throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> instrumentationClass = findLoadedClass(name);
      if (null != instrumentationClass) {
        return instrumentationClass;
      } else {
        // load bytecode from dd-java-agent jar, but define the class locally
        byte[] buf = ((DatadogClassLoader) getParent()).loadClassBytes(name);
        return defineClass(name, buf, 0, buf.length, agentCodeSource);
      }
    }
  }

  @Override
  public String toString() {
    return "instrumentation";
  }
}
