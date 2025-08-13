package datadog.trace.instrumentation.java.lang.management;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.environment.SystemProperties;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.jmx.MBeanServerRegistry;
import javax.management.MBeanServer;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class CustomMBeanBuilderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForConfiguredType, Instrumenter.HasMethodAdvice {

  private final String customBuilder;

  public CustomMBeanBuilderInstrumentation() {
    super("java-lang-management");

    customBuilder = SystemProperties.get("javax.management.builder.initial");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && customBuilder != null && !customBuilder.isEmpty();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("newMBeanServer")).and(returns(named("javax.management.MBeanServer"))),
        this.getClass().getName() + "$StoreMBeanServerAdvice");
  }

  @Override
  public String configuredMatchingType() {
    return customBuilder;
  }

  public static class StoreMBeanServerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterCreation(@Advice.Return final MBeanServer mbeanServer) {
      MBeanServerRegistry.putServer(mbeanServer.getClass().getName(), mbeanServer);
    }
  }
}
