package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import net.bytebuddy.asm.Advice;
import org.apache.spark.SparkConf;
import org.apache.spark.scheduler.SparkListenerInterface;
import org.slf4j.Logger;
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
    public static void exit(@Advice.This Object self, @Advice.FieldValue("conf") SparkConf conf) {
      //      try {
      Logger log = LoggerFactory.getLogger(Config.class);
      log.info(
          "OLSLA: ADSL classloader: ({}) {}",
          System.identityHashCode(AbstractDatadogSparkListener.class.getClassLoader()),
          AbstractDatadogSparkListener.class.getClassLoader());
      try {
        AbstractDatadogSparkListener.listener.setupOpenLineage(conf, (SparkListenerInterface) self);
      } catch (Throwable t) {
      }
      try {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread()
            .setContextClassLoader(AbstractDatadogSparkListener.class.getClassLoader());
        AbstractDatadogSparkListener.listener.setupOpenLineage(conf, (SparkListenerInterface) self);
        Thread.currentThread().setContextClassLoader(cl);
      } catch (Throwable t) {
      }

      //        log.debug(
      //            "ADSL classloader ({}) {}",
      //            System.identityHashCode(AbstractDatadogSparkListener.class.getClassLoader()),
      //            AbstractDatadogSparkListener.class.getClassLoader());
      //        log.debug(
      //            "Current classloader ({}) {}",
      //            System.identityHashCode(Thread.currentThread().getContextClassLoader()),
      //            Thread.currentThread().getContextClassLoader().toString());
      //        log.debug("Got OpenLineageSparkListener: {}", self);
      //        AbstractDatadogSparkListener.openLineageSparkListener.set((SparkListenerInterface)
      // self);
      //        log.debug(
      //            "Set OpenLineageSparkListener: {}",
      //            AbstractDatadogSparkListener.openLineageSparkListener.get());
      //        log.debug("Check for self: {}", self);
      //        if (AbstractDatadogSparkListener.openLineageSparkListener.get() != null) {
      //          log.debug(
      //              "Detected OpenLineageSparkListener, passed to DatadogSparkListener {} on
      // ClassLoader ({}) {}",
      //
      // AbstractDatadogSparkListener.openLineageSparkListener.get().getClass().getName(),
      //              System.identityHashCode(
      //                  AbstractDatadogSparkListener.openLineageSparkListener
      //                      .get()
      //                      .getClass()
      //                      .getClassLoader()),
      //              AbstractDatadogSparkListener.openLineageSparkListener
      //                  .get()
      //                  .getClass()
      //                  .getClassLoader());
      //        } else {
      //          log.debug("WTF it's null");
      //        }
      //      } catch (Exception e) {
      //        LoggerFactory.getLogger(Config.class)
      //            .error("Failed to set OpenLineageSparkListener: {}", e.getMessage());
      //      }

    }
  }
}
