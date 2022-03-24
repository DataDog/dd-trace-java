package datadog.trace.agent.installer;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

/**
 * Intercepts transformation requests before ByteBuddy so we can perform some initial filtering.
 *
 * <p>This class is only used on Java 9+, for Java 7/8 see {@link DDClassFileTransformer}.
 */
public final class DDJava9ClassFileTransformer implements ClassFileTransformer {

  @Override
  public byte[] transform(
      final ClassLoader classLoader,
      final String internalClassName,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain,
      final byte[] classFileBuffer)
      throws IllegalClassFormatException {

    return null; // entry-point to our matchers
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

    return null; // entry-point to our matchers
  }
}
