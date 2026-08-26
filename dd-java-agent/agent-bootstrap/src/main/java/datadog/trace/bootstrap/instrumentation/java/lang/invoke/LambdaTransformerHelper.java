package datadog.trace.bootstrap.instrumentation.java.lang.invoke;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point invoked from instrumented {@code java.lang.invoke.InnerClassLambdaMetafactory}. The
 * metafactory instrumentation injects a call to {@link #transform(byte[], String, Class, Class)}
 * right after the lambda class bytes are generated and before the class is defined, so eligible
 * lambdas get the agent's field-injection and advice applied like ordinary classes.
 *
 * <p>The JVM still defines the lambda as a hidden/anonymous class; only the bytes it defines are
 * replaced. This never throws: any problem returns the original bytes (mirroring how {@code
 * sun.instrument.TransformerManager} swallows {@code ClassFileTransformer} errors).
 */
public final class LambdaTransformerHelper {
  private static final Logger log = LoggerFactory.getLogger(LambdaTransformerHelper.class);

  // While transforming a lambda the agent / ByteBuddy may itself create lambdas; we must not
  // recurse into transformation for those.
  private static final ThreadLocal<Boolean> TRANSFORMING = new ThreadLocal<>();

  private LambdaTransformerHelper() {}

  /**
   * @param classBytes the generated lambda class bytes (on the stack from {@code toByteArray()})
   * @param lambdaClassName internal (slash-separated) name of the generated lambda class
   * @param targetClass the class declaring the lambda
   * @param interfaceClass the functional interface implemented by the lambda
   * @return possibly transformed bytes; the original bytes on any failure
   */
  public static byte[] transform(
      byte[] classBytes,
      String lambdaClassName,
      Class<?> targetClass,
      Class<?> interfaceClass) {
    try {
      // Only Runnable lambdas benefit from field-backed executor context propagation. Avoid sending
      // every other lambda through the agent's full matching and transformation pipeline.
      if (interfaceClass == null || !Runnable.class.isAssignableFrom(interfaceClass)) {
        return classBytes;
      }
      LambdaTransformer transformer = LambdaTransformerHolder.get();
      if (transformer == null) {
        log.debug("Lambda {} skipped: no transformer registered", lambdaClassName);
        return classBytes;
      }
      if (targetClass == null) {
        log.debug("Lambda {} skipped: no target class", lambdaClassName);
        return classBytes;
      }
      // Skip lambdas declared by the agent itself to avoid self-instrumentation and recursion.
      String targetName = targetClass.getName();
      if (targetName.startsWith("datadog.") || targetName.startsWith("net.bytebuddy.")) {
        log.debug("Lambda {} skipped: declared by the agent", lambdaClassName);
        return classBytes;
      }
      if (Boolean.TRUE.equals(TRANSFORMING.get())) {
        log.debug("Lambda {} skipped: re-entrant transform", lambdaClassName);
        return classBytes;
      }
      TRANSFORMING.set(Boolean.TRUE);
      try {
        byte[] result = transformer.transform(lambdaClassName, targetClass, classBytes);
        if (result == null) {
          log.debug("Lambda {} not transformed", lambdaClassName);
          return classBytes;
        }
        return result;
      } finally {
        TRANSFORMING.set(Boolean.FALSE);
      }
    } catch (Throwable e) {
      log.debug("Lambda {} skipped: {}", lambdaClassName, e.toString());
      return classBytes;
    }
  }
}
