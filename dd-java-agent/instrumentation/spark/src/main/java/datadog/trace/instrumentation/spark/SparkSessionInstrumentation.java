package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.spark.SparkSessionUtils.SESSION_UTILS;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.apache.spark.SparkContext;

@AutoService(InstrumenterModule.class)
public class SparkSessionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public SparkSessionInstrumentation() {
    super("spark-streaming");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.spark.sql.SparkSession";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SparkSessionUtils", packageName + ".SparkConfUtils",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(isDeclaredBy(named("org.apache.spark.sql.SparkSession"))),
        SparkSessionInstrumentation.class.getName() + "$SessionCreatedAdvice");
  }

  public static final class SessionCreatedAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.Argument(0) SparkContext context) {
      SESSION_UTILS.updatePreferredServiceName(context);
      return null;
    }
  }
}
