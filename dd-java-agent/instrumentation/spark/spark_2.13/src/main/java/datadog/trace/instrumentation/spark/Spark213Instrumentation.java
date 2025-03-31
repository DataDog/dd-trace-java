package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import net.bytebuddy.asm.Advice;
import org.apache.spark.SparkContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public class Spark213Instrumentation extends AbstractSparkInstrumentation {
  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AbstractDatadogSparkListener",
      packageName + ".DatabricksParentContext",
      packageName + ".OpenlineageParentContext",
      packageName + ".DatadogSpark213Listener",
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
  public void methodAdvice(MethodTransformer transformer) {
    super.methodAdvice(transformer);

    transformer.applyAdvice(
        isMethod()
            .and(named("setupAndStartListenerBus"))
            .and(isDeclaredBy(named("org.apache.spark.SparkContext")))
            .and(takesNoArguments()),
        Spark213Instrumentation.class.getName() + "$InjectListener");
  }

  public static class InjectListener {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This SparkContext sparkContext) {
      // checking whether OpenLineage integration is enabled, available and that it supports tags
      Logger log = LoggerFactory.getLogger("Spark212InjectListener");
      if (Config.get().isDataJobsOpenLineageEnabled()
          && AbstractDatadogSparkListener.classIsLoadable(
              "io.openlineage.spark.agent.OpenLineageSparkListener")
          && AbstractDatadogSparkListener.classIsLoadable(
              "io.openlineage.spark.agent.facets.builder.TagsRunFacetBuilder")) {
        if (!sparkContext.conf().contains("spark.extraListeners")) {
          log.debug("spark.extraListeners does not contain any listeners. Adding OpenLineage");
          sparkContext
              .conf()
              .set("spark.extraListeners", "io.openlineage.spark.agent.OpenLineageSparkListener");
        } else {
          String extraListeners = sparkContext.conf().get("spark.extraListeners");
          if (!extraListeners.contains("io.openlineage.spark.agent.OpenLineageSparkListener")) {
            log.debug(
                "spark.extraListeners does contain listeners {}. Adding OpenLineage",
                extraListeners);
            sparkContext
                .conf()
                .set(
                    "spark.extraListeners",
                    extraListeners + ",io.openlineage.spark.agent.OpenLineageSparkListener");
          }
        }
      }

      // We want to add the Datadog listener as the first listener
      AbstractDatadogSparkListener.listener =
          new DatadogSpark213Listener(
              sparkContext.getConf(), sparkContext.applicationId(), sparkContext.version());
      sparkContext.listenerBus().addToSharedQueue(AbstractDatadogSparkListener.listener);
    }
  }
}
