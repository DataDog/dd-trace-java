package datadog.trace.instrumentation.java.lang.management;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.util.AgentThreadFactory;
import javax.management.MBeanServer;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class AgentMBeanServerUpgradeInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.ForBootstrap {

  public AgentMBeanServerUpgradeInstrumentation() {
    super("java-lang-management");
  }

  @Override
  public boolean isEnabled() {
    if (!super.isEnabled()) {
      return false;
    }
    String customBuilder = System.getProperty("javax.management.builder.initial");
    return customBuilder != null && !customBuilder.isEmpty();
  }

  @Override
  public String instrumentedType() {
    return "java.lang.management.ManagementFactory";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("getPlatformMBeanServer"))
            .and(returns(named("javax.management.MBeanServer"))),
        this.getClass().getName() + "$ReplaceMBeanServerAdvice");
  }

  public static class ReplaceMBeanServerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecution(@Advice.Return(readOnly = false) MBeanServer mBeanServer) {
      MBeanServer cached = InstanceStore.of(MBeanServer.class).get("");
      // only replace for an agent internal call.
      if (cached != null
          && Thread.currentThread().getThreadGroup() == AgentThreadFactory.AGENT_THREAD_GROUP) {
        mBeanServer = cached;
      }
    }
  }
}
