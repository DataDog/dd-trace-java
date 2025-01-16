package datadog.trace.instrumentation.ognl;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class OgnlInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public OgnlInstrumentation() {
    super("ognl");
  }

  @Override
  public String instrumentedType() {
    return "ognl.Ognl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("parseExpression")).and(isStatic()).and(takesArguments(String.class)),
        OgnlInstrumentation.class.getName() + "$OgnlParseExpressionAdvice");
  }

  static class OgnlParseExpressionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before(@Advice.Argument(0) String expression) {
      AgentSpan parentSpan = activeSpan();
      if (parentSpan == null) {
        return;
      }

      AgentSpan agentSpan = startSpan("ognl.parse", parentSpan.context());
      agentSpan.setTag("ognl.expression", expression);
      agentSpan.finish();
    }
  }
}
