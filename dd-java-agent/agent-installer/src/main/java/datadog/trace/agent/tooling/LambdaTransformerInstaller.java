package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnores.isIgnored;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import datadog.trace.bootstrap.instrumentation.java.lang.invoke.LambdaTransformer;
import datadog.trace.bootstrap.instrumentation.java.lang.invoke.LambdaTransformerHolder;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.function.Function;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes the lambda transformer before installation can trigger retransformation. */
final class LambdaTransformerInstaller extends AgentBuilder.InstallationListener.Adapter {
  private static final Logger log = LoggerFactory.getLogger(LambdaTransformerInstaller.class);

  private final String[] lambdaInterfaces;

  LambdaTransformerInstaller(String[] lambdaInterfaces) {
    this.lambdaInterfaces = lambdaInterfaces;
  }

  @Override
  public void onBeforeInstall(
      Instrumentation instrumentation, ResettableClassFileTransformer classFileTransformer) {
    registerLambdaTransformer(classFileTransformer);
  }

  @Override
  public Throwable onError(
      Instrumentation instrumentation,
      ResettableClassFileTransformer classFileTransformer,
      Throwable throwable) {
    clearLambdaTransformer();
    return throwable;
  }

  private void registerLambdaTransformer(ClassFileTransformer classFileTransformer) {
    LambdaTransformer transformer = newLambdaTransformer(classFileTransformer);
    LambdaTransformerHolder.set(
        transformer != null ? filterLambdaTransformer(transformer, lambdaInterfaces) : null);
  }

  private static void clearLambdaTransformer() {
    LambdaTransformerHolder.set(null);
  }

  static LambdaTransformer filterLambdaTransformer(
      LambdaTransformer transformer, String[] lambdaInterfaces) {
    return new FilteringLambdaTransformer(transformer, lambdaInterfaces);
  }

  /**
   * Java 9+ requires the module-aware transformer for injected read edges. Failure must disable
   * lambda transformation rather than fall back to the module-less overload.
   */
  @SuppressWarnings("unchecked")
  private static LambdaTransformer newLambdaTransformer(ClassFileTransformer classFileTransformer) {
    if (JavaVirtualMachine.isJavaVersionAtLeast(9)) {
      try {
        Function<ClassFileTransformer, LambdaTransformer> factory =
            (Function<ClassFileTransformer, LambdaTransformer>)
                Instrumenter.class
                    .getClassLoader()
                    .loadClass("datadog.trace.agent.tooling.bytebuddy.DDJava9LambdaTransformer")
                    .getField("FACTORY")
                    .get(null);
        return factory.apply(classFileTransformer);
      } catch (Throwable error) {
        log.debug(
            "Problem loading Java 9 lambda transformer, disabling lambda transformation", error);
        return null;
      }
    }
    return new Java8LambdaTransformer(classFileTransformer);
  }

  private static final class FilteringLambdaTransformer implements LambdaTransformer {
    private final LambdaTransformer delegate;
    private final String[] lambdaInterfaces;

    private FilteringLambdaTransformer(LambdaTransformer delegate, String[] lambdaInterfaces) {
      this.delegate = delegate;
      this.lambdaInterfaces = lambdaInterfaces;
    }

    @Override
    public byte[] transform(
        String className, Class<?> targetClass, byte[] classBytes, String interfaceClassName) {
      for (String enabledInterface : lambdaInterfaces) {
        if (enabledInterface.equals(interfaceClassName)) {
          // Apply the system-level name filter before entering the full transformer pipeline.
          if (isIgnored(targetClass.getName(), true)) {
            return null;
          }
          return delegate.transform(className, targetClass, classBytes, interfaceClassName);
        }
      }
      return null;
    }
  }

  private static final class Java8LambdaTransformer implements LambdaTransformer {
    private final ClassFileTransformer classFileTransformer;

    private Java8LambdaTransformer(ClassFileTransformer classFileTransformer) {
      this.classFileTransformer = classFileTransformer;
    }

    @Override
    public byte[] transform(
        String className, Class<?> targetClass, byte[] classBytes, String interfaceClassName) {
      TypePoolFacade.beginLambdaTransform(interfaceClassName);
      try {
        return classFileTransformer.transform(
            targetClass.getClassLoader(),
            className,
            null,
            targetClass.getProtectionDomain(),
            classBytes);
      } catch (Throwable error) {
        log.debug(
            "Problem transforming generated lambda {}, leaving it unchanged", className, error);
        return null;
      } finally {
        TypePoolFacade.endLambdaTransform();
      }
    }
  }
}
