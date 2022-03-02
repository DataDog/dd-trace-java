package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.canSkipClassLoaderByName;

import datadog.trace.api.Config;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Arrays;
import net.bytebuddy.agent.builder.AgentBuilder.TransformerDecorator;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intercepts transformation requests before ByteBuddy so we can perform some initial filtering.
 *
 * <p>This class is only used on Java 7/8, for Java 9+ see {@link DDJava9ClassFileTransformer}.
 */
public final class DDClassFileTransformer extends ResettableClassFileTransformer.WithDelegation
    implements DDAsyncTransformer.TransformTask {
  private static final Logger log = LoggerFactory.getLogger(DDClassFileTransformer.class);

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
        if (null == classBeingRedefined) {
          return asyncTransformer.awaitTransform(
              null, classLoader, internalClassName, protectionDomain, classFileBuffer);
        }
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
      final ProtectionDomain protectionDomain,
      final byte[] classFileBuffer)
      throws IllegalClassFormatException {

    try {
      byte[] buf =
          classFileTransformer.transform(
              classLoader, internalClassName, null, protectionDomain, classFileBuffer);
      if (buf != null && !Arrays.equals(classFileBuffer, buf)) {
        log.info("***** TRANSFORM SUCCESS {}, {}, {}", internalClassName, javaModule, classLoader);
      }
      return buf;
    } catch (IllegalClassFormatException | RuntimeException | Error e) {
      log.info("***** TRANSFORM FAILURE {}, {}, {}", internalClassName, javaModule, classLoader, e);
      throw e;
    }
  }
}
