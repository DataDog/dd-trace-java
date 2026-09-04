package datadog.trace.bootstrap.instrumentation.java.lang.invoke;

/** Transforms a generated lambda class before it is defined. */
public interface LambdaTransformer {
  /**
   * @param slashClassName internal (slash-separated) name of the generated lambda class
   * @param targetClass the class declaring the lambda
   * @param classBytes the freshly generated lambda class bytes
   * @param interfaceClassName the functional interface implemented by the lambda
   * @return the transformed bytes, or {@code null}/the original bytes if unchanged
   */
  byte[] transform(
      String slashClassName, Class<?> targetClass, byte[] classBytes, String interfaceClassName);
}
