package datadog.trace.instrumentation.java.lang.management;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.util.AgentThreadFactory;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class WebsphereSecurityInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public WebsphereSecurityInstrumentation() {
    super("websphere-security");
  }

  @Override
  public String instrumentedType() {
    return "com.ibm.ws.management.util.SecurityHelper";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("isSecurityEnabled")).and(returns(boolean.class)),
        this.getClass().getName() + "$DisableSecurityAdvice");
  }

  public static class DisableSecurityAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(@Advice.Return(readOnly = false) boolean securityEnabled) {
      if (AgentThreadFactory.AGENT_THREAD_GROUP == Thread.currentThread().getThreadGroup()) {
        securityEnabled = false;
      }
    }
  }
}
