package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import org.apache.commons.lang.reflect.FieldUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.scheduler.SparkListenerInterface;

@AutoService(InstrumenterModule.class)
public class OpenLineageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public OpenLineageInstrumentation() {
    super("openlineage-spark");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AbstractDatadogSparkListener",
      packageName + ".DatabricksParentContext",
      packageName + ".OpenlineageParentContext",
      packageName + ".RemoveEldestHashMap",
      packageName + ".SparkAggregatedTaskMetrics",
      packageName + ".SparkConfAllowList",
      packageName + ".SparkSQLUtils",
      packageName + ".SparkSQLUtils$SparkPlanInfoForStage",
      packageName + ".SparkSQLUtils$AccumulatorWithStage",
    };
  }

  @Override
  public boolean defaultEnabled() {
    return true;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.openlineage.spark.agent.OpenLineageSparkListener", "org.apache.spark.util.Utils"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // LiveListenerBus class is used when running in a YARN cluster
    transformer.applyAdvice(
        isConstructor()
            .and(isDeclaredBy(named("io.openlineage.spark.agent.OpenLineageSparkListener")))
            .and(takesArgument(0, named("org.apache.spark.SparkConf"))),
        OpenLineageInstrumentation.class.getName() + "$OpenLineageSparkListenerAdvice");

    // LiveListenerBus class is used when running in a YARN cluster
    transformer.applyAdvice(
        isMethod()
            .and(named("addToSharedQueue"))
            .and(takesArgument(0, named("org.apache.spark.scheduler.SparkListenerInterface")))
            .and(isDeclaredBy(named("org.apache.spark.scheduler.LiveListenerBus"))),
        AbstractSparkInstrumentation.class.getName() + "$LiveListenerBusAdvice");
  }

  public static class OpenLineageSparkListenerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object self) throws IllegalAccessException {
      AbstractDatadogSparkListener.openLineageSparkListener = (SparkListenerInterface) self;
      AbstractDatadogSparkListener.openLineageSparkConf =
          (SparkConf) FieldUtils.getField(self.getClass(), "conf", true).get(self);
    }
  }

  public static class LiveListenerBusAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(@Advice.Argument(0) SparkListenerInterface listener) {
      if (listener == null || listener.getClass().getCanonicalName() == null) {
        return false;
      }
      if (listener
          .getClass()
          .getCanonicalName()
          .equals("io.openlineage.spark.agent.OpenLineageSparkListener")) {
        return true;
      }
      return false;
    }
  }
}
