package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.canSkipClassLoaderByName;

import datadog.trace.api.Config;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder.TransformerDecorator;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;

/**
 * Intercepts transformation requests before ByteBuddy so we can perform some initial filtering.
 *
 * <p>This class is only used on Java 7/8, for Java 9+ see {@link DDJava9ClassFileTransformer}.
 */
public final class DDClassFileTransformer extends ResettableClassFileTransformer.WithDelegation
    implements DDAsyncTransformer.TransformTask {

  public static final TransformerDecorator DECORATOR =
      new TransformerDecorator() {
        @Override
        public ResettableClassFileTransformer decorate(ResettableClassFileTransformer delegate) {
          return new DDClassFileTransformer(delegate);
        }
      };

  private static final boolean ASYNC_TRANSFORMATION_ENABLED =
      Config.get().isAsyncTransformationEnabled();

  private final DDAsyncTransformer asyncTransformer =
      ASYNC_TRANSFORMATION_ENABLED ? new DDAsyncTransformer(this) : null;

  public DDClassFileTransformer(final ResettableClassFileTransformer delegate) {
    super(delegate);
  }

  @Override
  public byte[] transform(
      final ClassLoader classLoader,
      final String internalClassName,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain,
      final byte[] classFileBuffer)
      throws IllegalClassFormatException {

    if (null == internalClassName) {
      return null;
    }

    if (null != classLoader) {
      if (canSkipClassLoaderByName(classLoader)) {
        return null;
      } else if (ASYNC_TRANSFORMATION_ENABLED) {
        return asyncTransformer.awaitTransform(
            null,
            classLoader,
            internalClassName,
            classBeingRedefined,
            protectionDomain,
            classFileBuffer);
      }
    }

    return classFileTransformer.transform(
        classLoader, internalClassName, classBeingRedefined, protectionDomain, classFileBuffer);
  }

  @Override
  public byte[] doTransform(
      final Object javaModule,
      final ClassLoader classLoader,
      final String internalClassName,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain,
      final byte[] classFileBuffer)
      throws IllegalClassFormatException {

    return classFileTransformer.transform(
        classLoader, internalClassName, classBeingRedefined, protectionDomain, classFileBuffer);
  }
}
