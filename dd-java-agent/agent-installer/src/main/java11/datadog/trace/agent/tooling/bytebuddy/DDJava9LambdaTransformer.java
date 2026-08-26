package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.bootstrap.instrumentation.java.lang.invoke.LambdaTransformer;
import java.lang.instrument.ClassFileTransformer;
import java.util.function.Function;

/**
 * Routes generated lambda classes through the agent's transformer, passing the module of the class
 * that declares the lambda.
 *
 * <p>The module-less {@code transform} overload reports {@code JavaModule.UNSUPPORTED} to
 * ByteBuddy, which then skips the read edge that field-injected classes need to reach {@code
 * FieldBackedContextAccessor}. A lambda declared in a named module would be transformed
 * successfully and then fail to define, surfacing as an {@code InternalError} at the lambda's call
 * site.
 *
 * <p>This class is only used on Java 9+; for Java 8 the module-less overload is complete.
 */
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
    }
  }
}
