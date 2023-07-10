package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.apache.spark.SparkContext;

@AutoService(Instrumenter.class)
public class SparkInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {

  public SparkInstrumentation() {
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
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatabricksParentContext",
      packageName + ".DatadogSparkListener",
      packageName + ".SparkAggregatedTaskMetrics",
      packageName + ".SparkConfAllowList",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("setupAndStartListenerBus"))
            .and(isDeclaredBy(named("org.apache.spark.SparkContext")))
            .and(takesNoArguments()),
        SparkInstrumentation.class.getName() + "$InjectListener");

    // SparkSubmit class used for non YARN/Mesos environment
    transformation.applyAdvice(
        isMethod()
            .and(nameEndsWith("runMain"))
            .and(isDeclaredBy(named("org.apache.spark.deploy.SparkSubmit"))),
        SparkInstrumentation.class.getName() + "$RunMainAdvice");

    // ApplicationMaster class is used when running in a YARN cluster
    transformation.applyAdvice(
        isMethod()
            .and(named("finish"))
            .and(isDeclaredBy(named("org.apache.spark.deploy.yarn.ApplicationMaster"))),
        SparkInstrumentation.class.getName() + "$YarnFinishAdvice");
  }

  public static class InjectListener {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This SparkContext sparkContext) {
      DatadogSparkListener.listener =
          new DatadogSparkListener(
              sparkContext.getConf(), sparkContext.applicationId(), sparkContext.version());
      sparkContext.listenerBus().addToSharedQueue(DatadogSparkListener.listener);
    }
  }

  public static class RunMainAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      DatadogSparkListener.finishTraceOnApplicationEnd = false;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Thrown Throwable throwable) {
      if (DatadogSparkListener.listener != null) {
        DatadogSparkListener.listener.finishApplication(
            System.currentTimeMillis(), throwable, 0, null);
      }
    }
  }

  public static class YarnFinishAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(1) int exitCode, @Advice.Argument(2) String msg) {
      if (DatadogSparkListener.listener != null) {
        DatadogSparkListener.listener.finishApplication(
            System.currentTimeMillis(), null, exitCode, msg);
      }
    }
  }
}
