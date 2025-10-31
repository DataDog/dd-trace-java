package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Constructor;
import net.bytebuddy.asm.Advice;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Predef;
import scala.collection.JavaConverters;
import scala.collection.immutable.Map;

@AutoService(InstrumenterModule.class)
public class Spark212Instrumentation extends AbstractSparkInstrumentation {
  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AbstractDatadogSparkListener",
      packageName + ".AbstractSparkPlanSerializer",
      packageName + ".DatabricksParentContext",
      packageName + ".OpenlineageParentContext",
      packageName + ".DatadogSpark212Listener",
      packageName + ".PredeterminedTraceIdContext",
      packageName + ".RemoveEldestHashMap",
      packageName + ".SparkAggregatedTaskMetrics",
      packageName + ".SparkConfAllowList",
      packageName + ".SparkSQLUtils",
      packageName + ".SparkSQLUtils$SparkPlanInfoForStage",
      packageName + ".SparkSQLUtils$AccumulatorWithStage",
      packageName + ".Spark212PlanSerializer"
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
        Spark212Instrumentation.class.getName() + "$InjectListener");

    transformer.applyAdvice(
        isMethod()
            .and(named("fromSparkPlan"))
            .and(takesArgument(0, named("org.apache.spark.sql.execution.SparkPlan")))
            .and(isDeclaredBy(named("org.apache.spark.sql.execution.SparkPlanInfo$"))),
        Spark212Instrumentation.class.getName() + "$SparkPlanInfoAdvice");
  }

  public static class InjectListener {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This SparkContext sparkContext) {
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
          new DatadogSpark212Listener(
              sparkContext.getConf(), sparkContext.applicationId(), sparkContext.version());
      sparkContext.listenerBus().addToSharedQueue(AbstractDatadogSparkListener.listener);
    }
  }

  public static class SparkPlanInfoAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    @SuppressForbidden
    public static void exit(
        @Advice.Return(readOnly = false) SparkPlanInfo planInfo,
        @Advice.Argument(0) SparkPlan plan) {
      if (planInfo.metadata().size() == 0
          && (Config.get().isDataJobsParseSparkPlanEnabled()
              || Config.get().isDataJobsExperimentalFeaturesEnabled())) {
        Spark212PlanSerializer planUtils = new Spark212PlanSerializer();
        Map<String, String> meta =
            JavaConverters.mapAsScalaMap(planUtils.extractFormattedProduct(plan))
                .toMap(Predef.$conforms());
        try {
          Constructor<?> targetCtor = null;
          for (Constructor<?> c : SparkPlanInfo.class.getConstructors()) {
            if (c.getParameterCount() == 5) {
              targetCtor = c;
              break;
            }
          }
          if (targetCtor != null) {
            Object newInst =
                targetCtor.newInstance(
                    planInfo.nodeName(),
                    planInfo.simpleString(),
                    planInfo.children(),
                    meta,
                    planInfo.metrics());
            planInfo = (SparkPlanInfo) newInst;
          }
        } catch (Throwable ignored) {
        }
      }
    }
  }
}
