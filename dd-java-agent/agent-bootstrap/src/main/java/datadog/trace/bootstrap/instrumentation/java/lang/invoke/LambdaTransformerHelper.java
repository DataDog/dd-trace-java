package datadog.trace.bootstrap.instrumentation.java.lang.invoke;

/**
 * Entry point invoked from instrumented {@code java.lang.invoke.InnerClassLambdaMetafactory}. The
 * metafactory instrumentation injects a call to {@link #transform(byte[], String, Class)} right
 * after the lambda class bytes are generated and before the class is defined, so the generated
 * lambda gets the agent's field-injection and advice applied like an ordinary class.
 *
 * <p>The JVM still defines the lambda as a hidden/anonymous class; only the bytes it defines are
 * replaced. This never throws: any problem returns the original bytes (mirroring how {@code
 * sun.instrument.TransformerManager} swallows {@code ClassFileTransformer} errors).
 */
public final class LambdaTransformerHelper {
  // While transforming a lambda the agent / ByteBuddy may itself create lambdas; we must not
  // recurse into transformation for those.
  private static final ThreadLocal<Boolean> TRANSFORMING = new ThreadLocal<>();

  private LambdaTransformerHelper() {}

  /**
   * @param classBytes the generated lambda class bytes (on the stack from {@code toByteArray()})
   * @param lambdaClassName internal (slash-separated) name of the generated lambda class
   * @param targetClass the class declaring the lambda
   * @return possibly transformed bytes; the original bytes on any failure
   */
  public static byte[] transform(byte[] classBytes, String lambdaClassName, Class<?> targetClass) {
    try {
      LambdaTransformer transformer = LambdaTransformerHolder.get();
      if (transformer == null || targetClass == null) {
        return classBytes;
      }
      // Skip lambdas declared by the agent itself to avoid self-instrumentation and recursion.
      String targetName = targetClass.getName();
      if (targetName.startsWith("datadog.") || targetName.startsWith("net.bytebuddy.")) {
        return classBytes;
      }
      if (Boolean.TRUE.equals(TRANSFORMING.get())) {
        return classBytes;
      }
      TRANSFORMING.set(Boolean.TRUE);
      try {
        byte[] result = transformer.transform(lambdaClassName, targetClass, classBytes);
        return result != null ? result : classBytes;
      } finally {
        TRANSFORMING.set(Boolean.FALSE);
      }
    } catch (Throwable ignored) {
      return classBytes;
    }
  }
}
