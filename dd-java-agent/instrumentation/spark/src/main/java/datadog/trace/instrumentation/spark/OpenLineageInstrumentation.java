package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstanceStore;
import net.bytebuddy.asm.Advice;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public class OpenLineageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public OpenLineageInstrumentation() {
    super("spark-openlineage");
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
    return false;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"io.openlineage.spark.agent.OpenLineageSparkListener"};
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
    public static void exit(@Advice.This Object self, @Advice.FieldValue("conf") SparkConf conf)
        throws IllegalAccessException {
      Logger log = LoggerFactory.getLogger("OpenLineageSparkListenerAdvice");
      if (!Config.get().isDataJobsOpenLineageEnabled()) {
        log.debug(
            "OpenLineage - Data Jobs integration disabled. Not manipulating OpenLineageSparkListener");
        return;
      }
      InstanceStore.of(SparkConf.class).put("openLineageSparkConf", conf);
    }
  }
}
