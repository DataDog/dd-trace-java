package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import org.apache.spark.SparkConf;
import org.apache.spark.scheduler.SparkListenerInterface;
import org.slf4j.LoggerFactory;

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
      packageName + ".PredeterminedTraceIdContext",
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
  }

  public static class OpenLineageSparkListenerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object self) throws IllegalAccessException {
      LoggerFactory.getLogger(Config.class).debug("Checking for OpenLineageSparkListener");
      try {
        Field conf = self.getClass().getDeclaredField("conf");
        conf.setAccessible(true);
        AbstractDatadogSparkListener.openLineageSparkConf = (SparkConf) conf.get(self);
        AbstractDatadogSparkListener.openLineageSparkListener = (SparkListenerInterface) self;
        LoggerFactory.getLogger(Config.class)
            .debug("Detected OpenLineageSparkListener, passed to DatadogSparkListener");
      } catch (NoSuchFieldException | IllegalAccessException e) {
        LoggerFactory.getLogger(Config.class)
            .debug("Failed to pass OpenLineageSparkListener to DatadogSparkListener", e);
      }
    }
  }
}
