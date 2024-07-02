package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import org.apache.spark.SparkContext;

@AutoService(InstrumenterModule.class)
public class Spark211Instrumentation extends AbstractSparkInstrumentation {
  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AbstractDatadogSparkListener",
      packageName + ".DatabricksParentContext",
      packageName + ".DatadogSpark211Listener",
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
        Spark211Instrumentation.class.getName() + "$InjectListener");
  }

  public static class InjectListener {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This SparkContext sparkContext) {
      AbstractDatadogSparkListener.listener =
          new DatadogSpark211Listener(
              sparkContext.getConf(), sparkContext.applicationId(), sparkContext.version());
      sparkContext.listenerBus().addToSharedQueue(AbstractDatadogSparkListener.listener);
    }
  }
}
