package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.canSkipClassLoaderByName;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder.TransformerDecorator;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;

/**
 * Intercepts transformation requests before ByteBuddy so we can perform some initial filtering.
 *
 * <p>This class is only used on Java 9+, for Java 7/8 see {@link DDClassFileTransformer}.
 */
public final class DDJava9ClassFileTransformer
    extends ResettableClassFileTransformer.WithDelegation {

  public static final TransformerDecorator DECORATOR =
      new TransformerDecorator() {
        @Override
        public ResettableClassFileTransformer decorate(
            final ResettableClassFileTransformer classFileTransformer) {
          return new DDJava9ClassFileTransformer(classFileTransformer);
        }
      };

  public DDJava9ClassFileTransformer(final ResettableClassFileTransformer classFileTransformer) {
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

    try {
      return classFileTransformer.transform(
          classLoader, internalClassName, classBeingRedefined, protectionDomain, classFileBuffer);
    } finally {
      SharedTypePools.endTransform();
    }
  }

  @Override
  public byte[] transform(
      final Module module,
      final ClassLoader classLoader,
      final String internalClassName,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain,
      final byte[] classFileBuffer)
      throws IllegalClassFormatException {

    if (null != classLoader && canSkipClassLoaderByName(classLoader)) {
      return null;
    }

    try {
      return classFileTransformer.transform(
          module,
          classLoader,
          internalClassName,
          classBeingRedefined,
          protectionDomain,
          classFileBuffer);
    } finally {
      SharedTypePools.endTransform();
    }
  }
}
