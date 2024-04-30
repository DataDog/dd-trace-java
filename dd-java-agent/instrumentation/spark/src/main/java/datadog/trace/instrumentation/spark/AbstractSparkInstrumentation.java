package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import org.apache.spark.deploy.SparkSubmitArguments;

public abstract class AbstractSparkInstrumentation extends InstrumenterModule.Tracing
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
    // Capture spark submit arguments
    transformer.applyAdvice(
        isMethod()
            .and(named("prepareSubmitEnvironment"))
            .and(takesArgument(0, named("org.apache.spark.deploy.SparkSubmitArguments")))
            .and(isDeclaredBy(named("org.apache.spark.deploy.SparkSubmit"))),
        AbstractSparkInstrumentation.class.getName() + "$PrepareSubmitEnvAdvice");

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

  public static class PrepareSubmitEnvAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) SparkSubmitArguments submitArgs) {

      // Using pyspark `python script.py`, spark JVM is launched as PythonGatewayServer, which is
      // exited using System.exit(0), leading to the exit advice not being called
      // https://github.com/apache/spark/blob/v3.5.1/core/src/main/scala/org/apache/spark/deploy/SparkSubmit.scala#L540-L542
      // https://github.com/apache/spark/blob/v3.5.1/core/src/main/scala/org/apache/spark/api/python/PythonGatewayServer.scala#L74
      if ("pyspark-shell".equals(submitArgs.primaryResource())) {
        AbstractDatadogSparkListener.isPysparkShell = true;

        // prepareSubmitEnvironment might be called before/after runMain depending on spark version
        AbstractDatadogSparkListener.finishTraceOnApplicationEnd = true;
      }
    }
  }

  public static class RunMainAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      if (!AbstractDatadogSparkListener.isPysparkShell) {
        AbstractDatadogSparkListener.finishTraceOnApplicationEnd = false;
      }
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
