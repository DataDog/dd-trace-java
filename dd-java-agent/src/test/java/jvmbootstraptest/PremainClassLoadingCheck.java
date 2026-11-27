package jvmbootstraptest;

import datadog.trace.bootstrap.AgentPreCheck;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Runs the packaged agent with an Instrumentation proxy that observes transformer registration. */
public final class PremainClassLoadingCheck implements InvocationHandler {
  private static PremainClassLoadingCheck check;

  private final Instrumentation instrumentation;
  private boolean transformerRegistered;

  private PremainClassLoadingCheck(Instrumentation instrumentation) {
    this.instrumentation = instrumentation;
  }

  public static void premain(String agentArgs, Instrumentation instrumentation) {
    check = new PremainClassLoadingCheck(instrumentation);
    Instrumentation observed =
        (Instrumentation)
            Proxy.newProxyInstance(
                PremainClassLoadingCheck.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                check);
    check.assertNoGuardedClassesLoaded();

    // The real agent is on the classpath, preserving its JAR code source and normal bootstrap path.
    AgentPreCheck.premain(null, observed);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    boolean registeringTransformer = false;
    if (method.getName().equals("addTransformer")) {
      String transformer = args[0].getClass().getName();
      registeringTransformer = transformer.startsWith("datadog.trace.agent.tooling.bytebuddy.");
      if (registeringTransformer) {
        assertNoGuardedClassesLoaded();
      }
    }
    try {
      Object result = method.invoke(instrumentation, args);
      if (registeringTransformer) {
        transformerRegistered = true;
      }
      return result;
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private void assertNoGuardedClassesLoaded() {
    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
      String name = type.getName();
      if (name.startsWith("java.util.concurrent.CompletableFuture")
          || name.equals("java.net.InetAddress")
          || name.startsWith("java.lang.ProcessBuilder")) {
        throw new AssertionError("Class loaded before transformer registration: " + name);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (check == null || !check.transformerRegistered) {
      throw new AssertionError("The Datadog transformer was not registered");
    }

    // Loading guarded classes after registration is legitimate application behavior.
    Class.forName("java.util.concurrent.CompletableFuture");
    Class.forName("java.net.InetAddress");
    Class.forName("java.lang.ProcessBuilder$Redirect");
  }
}
