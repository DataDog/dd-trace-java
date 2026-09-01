package datadog.trace.bootstrap.instrumentation.java.lang.invoke;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Transforms eligible lambda bytes before definition, falling back to the original on failure. */
public final class LambdaTransformerHelper {
  private static final Logger log = LoggerFactory.getLogger(LambdaTransformerHelper.class);

  // Agent transformation may itself create lambdas.
  private static final ThreadLocal<Boolean> TRANSFORMING = new ThreadLocal<>();

  private LambdaTransformerHelper() {}

  /**
   * @param classBytes the generated lambda class bytes
   * @param lambdaClassName internal (slash-separated) name of the generated lambda class
   * @param targetClass the class declaring the lambda
   * @param interfaceClass the functional interface implemented by the lambda
   * @return possibly transformed bytes; the original bytes on any failure
   */
  public static byte[] transform(
      byte[] classBytes, String lambdaClassName, Class<?> targetClass, Class<?> interfaceClass) {
    try {
      // Only exact allowlisted interfaces enter the transformer.
      if (interfaceClass == null || LambdaInterfaceNameTrie.apply(interfaceClass.getName()) != 1) {
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
        byte[] result =
            transformer.transform(
                lambdaClassName, targetClass, classBytes, interfaceClass.getName());
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
