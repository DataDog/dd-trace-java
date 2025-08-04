package datadog.trace.instrumentation.websphere_jmx;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.environment.SystemProperties;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.AgentThreadFactory;
import java.util.Collections;
import net.bytebuddy.asm.Advice;

/**
 * Grant JMXFetch access to the WebSphere Admin MBean without changing the server security config.
 */
@AutoService(InstrumenterModule.class)
public class WebsphereSecurityInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String customBuilder;

  public WebsphereSecurityInstrumentation() {
    super("websphere-jmx");

    customBuilder = SystemProperties.get("javax.management.builder.initial");
  }

  @Override
  public String instrumentedType() {
    return "com.ibm.ws.management.util.SecurityHelper";
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && "com.ibm.ws.management.PlatformMBeanServerBuilder".equals(customBuilder)
        // we must avoid loading the global Config while setting up instrumentation, so use the same
        // underlying provider call as Config.get().isJmxFetchIntegrationEnabled("websphere", false)
        && ConfigProvider.getInstance()
            .isEnabled(Collections.singletonList("websphere"), "jmxfetch.", ".enabled", false);
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
      // only grant access when we know the call is coming from one of our agent threads
      if (AgentThreadFactory.AGENT_THREAD_GROUP == Thread.currentThread().getThreadGroup()) {
        securityEnabled = false;
      }
    }
  }
}
