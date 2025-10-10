package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import net.bytebuddy.asm.Advice;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;
import scala.collection.immutable.HashMap;

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
      packageName + ".Spark213PlanUtils",
      packageName + ".CommonSparkPlanUtils",
    };
  }

  @Override
  public String[] knownMatchingTypes() {
    String[] res = new String[super.knownMatchingTypes().length + 1];
    int idx = 0;
    for (String match : super.knownMatchingTypes()) {
      res[idx] = match;
      idx++;
    }
    res[idx] = "org.apache.spark.sql.execution.SparkPlanInfo$";
    return res;
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

    transformer.applyAdvice(
        isMethod()
            .and(named("fromSparkPlan"))
            .and(takesArgument(0, named("org.apache.spark.sql.execution.SparkPlan")))
            .and(isDeclaredBy(named("org.apache.spark.sql.execution.SparkPlanInfo$"))),
        Spark213Instrumentation.class.getName() + "$SparkPlanInfoAdvice");
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

  public static class SparkPlanInfoAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(
        @Advice.Return(readOnly = false) SparkPlanInfo planInfo,
        @Advice.Argument(0) SparkPlan plan) {
      if (planInfo.metadata().size() == 0) {
        planInfo =
            new SparkPlanInfo(
                planInfo.nodeName(),
                planInfo.simpleString(),
                planInfo.children(),
                HashMap.from(
                    JavaConverters.asScala(Spark213PlanUtils.extractPlanProduct(plan)).toList()),
                planInfo.metrics());
      }
    }
  }
}
