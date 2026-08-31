package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import datadog.trace.bootstrap.instrumentation.java.lang.invoke.LambdaTransformer;
import java.lang.instrument.ClassFileTransformer;
import java.util.function.Function;

/** Routes generated lambdas through the module-aware Java 9+ transformer overload. */
public final class DDJava9LambdaTransformer implements LambdaTransformer {

  /** Read reflectively by the agent installer, which cannot name {@link Module} itself. */
  public static final Function<ClassFileTransformer, LambdaTransformer> FACTORY =
      new Function<ClassFileTransformer, LambdaTransformer>() {
        @Override
        public LambdaTransformer apply(ClassFileTransformer classFileTransformer) {
          return new DDJava9LambdaTransformer(classFileTransformer);
        }
      };

  private final ClassFileTransformer classFileTransformer;

  public DDJava9LambdaTransformer(ClassFileTransformer classFileTransformer) {
    this.classFileTransformer = classFileTransformer;
  }

  @Override
  public byte[] transform(String slashClassName, Class<?> targetClass, byte[] classBytes) {
    TypePoolFacade.beginLambdaTransform();
    try {
      return classFileTransformer.transform(
          targetClass.getModule(),
          targetClass.getClassLoader(),
          slashClassName,
          null,
          targetClass.getProtectionDomain(),
          classBytes);
    } catch (Throwable ignored) {
      return null;
    } finally {
      TypePoolFacade.endLambdaTransform();
    }
  }
}
