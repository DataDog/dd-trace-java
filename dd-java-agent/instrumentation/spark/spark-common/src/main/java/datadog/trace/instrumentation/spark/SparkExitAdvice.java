package datadog.trace.instrumentation.spark;

import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

class SparkExitAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) int exitCode) {
    try {
      // Using reflection as java.lang.* instrumentation have to be done at boostrap,
      // when spark classes have not been injected yet
      Class<?> klass =
          Thread.currentThread()
              .getContextClassLoader()
              .loadClass("datadog.trace.instrumentation.spark.AbstractDatadogSparkListener");
      Object datadogListener = klass.getDeclaredField("listener").get(null);
      if (datadogListener != null) {
        Method method =
            datadogListener
                .getClass()
                .getMethod(
                    "finishApplication", long.class, Throwable.class, int.class, String.class);
        method.invoke(datadogListener, System.currentTimeMillis(), null, exitCode, null);
      }
    } catch (Exception ignored) {
    }
  }
}
