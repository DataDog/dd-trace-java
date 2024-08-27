package datadog.trace.instrumentation.springboot;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * An instrumentation that set the service name according to what defined as
 * `spring.application.name`
 */
@AutoService(InstrumenterModule.class)
public class SpringApplicationInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {
  public SpringApplicationInstrumentation() {
    super("spring-boot");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.boot.SpringApplicationRunListeners";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DeploymentHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("environmentPrepared")
            .and(takesArgument(0, named("org.springframework.core.env.ConfigurableEnvironment"))),
        getClass().getName() + "$EnvironmentReadyV1Advice");
    // >= 2.4.0
    transformer.applyAdvice(
        named("environmentPrepared")
            .and(takesArgument(1, named("org.springframework.core.env.ConfigurableEnvironment"))),
        getClass().getName() + "$EnvironmentReadyV2Advice");
  }

  public static class EnvironmentReadyV1Advice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterEnvironmentPostProcessed(
        @Advice.Argument(0) final ConfigurableEnvironment environment) {
      if (environment == null
          || DeploymentHelper.runningFromWar
          || Config.get().isServiceNameSetByUser()) {
        return;
      }

      final String applicationName = environment.getProperty("spring.application.name");
      if (applicationName != null && !applicationName.isEmpty()) {
        AgentTracer.get().updatePreferredServiceName(applicationName);
      }
    }
  }

  public static class EnvironmentReadyV2Advice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterEnvironmentPostProcessed(
        @Advice.Argument(1) final ConfigurableEnvironment environment) {
      if (environment == null
          || DeploymentHelper.runningFromWar
          || Config.get().isServiceNameSetByUser()) {
        return;
      }

      final String applicationName = environment.getProperty("spring.application.name");
      if (applicationName != null && !applicationName.isEmpty()) {
        AgentTracer.get().updatePreferredServiceName(applicationName);
      }
    }
  }
}
