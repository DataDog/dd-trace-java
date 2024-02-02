package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

public abstract class AbstractSparkInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {

  public AbstractSparkInstrumentation() {
    super("spark", "apache-spark");
  }

  @Override
  public boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.spark.SparkContext",
      "org.apache.spark.deploy.SparkSubmit",
      "org.apache.spark.deploy.yarn.ApplicationMaster"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // SparkSubmit class used for non YARN/Mesos environment
    transformer.applyAdvice(
        isMethod()
            .and(nameEndsWith("runMain"))
            .and(isDeclaredBy(named("org.apache.spark.deploy.SparkSubmit"))),
        AbstractSparkInstrumentation.class.getName() + "$RunMainAdvice");

    // ApplicationMaster class is used when running in a YARN cluster
    transformer.applyAdvice(
        isMethod()
            .and(named("finish"))
            .and(isDeclaredBy(named("org.apache.spark.deploy.yarn.ApplicationMaster"))),
        AbstractSparkInstrumentation.class.getName() + "$YarnFinishAdvice");
  }

  public static class RunMainAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      AbstractDatadogSparkListener.finishTraceOnApplicationEnd = false;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Thrown Throwable throwable) {
      if (AbstractDatadogSparkListener.listener != null) {
        AbstractDatadogSparkListener.listener.finishApplication(
            System.currentTimeMillis(), throwable, 0, null);
      }
    }
  }

  public static class YarnFinishAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(1) int exitCode, @Advice.Argument(2) String msg) {
      if (AbstractDatadogSparkListener.listener != null) {
        AbstractDatadogSparkListener.listener.finishApplication(
            System.currentTimeMillis(), null, exitCode, msg);
      }
    }
  }
}
