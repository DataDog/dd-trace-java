package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.apache.spark.SparkContext;

@AutoService(Instrumenter.class)
public class SparkInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public SparkInstrumentation() {
    super("spark", "apache-spark");
  }

  @Override
  public boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "org.apache.spark.SparkContext";
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
        isMethod().and(named("setupAndStartListenerBus")).and(takesNoArguments()),
        SparkInstrumentation.class.getName() + "$InjectListener");
  }

  public static class InjectListener {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This SparkContext sparkContext) {
      DatadogSparkListener listener = new DatadogSparkListener(sparkContext);
      sparkContext.listenerBus().addToSharedQueue(listener);
    }
  }
}
