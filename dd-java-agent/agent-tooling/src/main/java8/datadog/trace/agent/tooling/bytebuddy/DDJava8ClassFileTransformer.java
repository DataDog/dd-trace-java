package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.canSkipClassLoaderByName;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder.TransformerDecorator;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;

/**
 * Intercepts transformation requests before ByteBuddy so we can perform some initial filtering.
 *
 * <p>This class is only used on Java 8, for Java 9+ see {@link DDJava9ClassFileTransformer}.
 */
public final class DDJava8ClassFileTransformer
    extends ResettableClassFileTransformer.WithDelegation {

  public static final TransformerDecorator DECORATOR =
      new TransformerDecorator() {
        @Override
        public ResettableClassFileTransformer decorate(
            final ResettableClassFileTransformer classFileTransformer) {
          return new DDJava8ClassFileTransformer(classFileTransformer);
        }
      };

  public DDJava8ClassFileTransformer(final ResettableClassFileTransformer classFileTransformer) {
    super(classFileTransformer);
  }

  @Override
  public byte[] transform(
      final ClassLoader classLoader,
      final String internalClassName,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain,
      final byte[] classFileBuffer)
      throws IllegalClassFormatException {

    if (null != classLoader && canSkipClassLoaderByName(classLoader)) {
      return null;
    }

    ClassTransformationEvent event =
        ClassTransformationEvent.beforeClassTransformation(internalClassName, classFileBuffer);
    byte[] result =
        classFileTransformer.transform(
            classLoader, internalClassName, classBeingRedefined, protectionDomain, classFileBuffer);
    event.afterClassTransformation(result);
    return result;
  }
}
