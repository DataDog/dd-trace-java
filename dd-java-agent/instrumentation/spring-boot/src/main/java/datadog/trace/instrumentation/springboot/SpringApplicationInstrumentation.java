package datadog.trace.instrumentation.springboot;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import net.bytebuddy.asm.Advice;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * This instrumentation only applies to SpringApplication being run as a jar because when deployed
 * as a war, the application will typically extend SpringBootServletInitializer and not use those
 * listeners.
 */
@AutoService(Instrumenter.class)
public class SpringApplicationInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public SpringApplicationInstrumentation() {
    super("spring-boot");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.boot.SpringApplicationRunListeners";
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
      if (environment == null || !Config.get().getServiceNaming().isMutable()) {
        return;
      }

      final String applicationName = environment.getProperty("spring.application.name");
      System.err.println("APP NAME " + applicationName);
      if (applicationName != null && !applicationName.isEmpty()) {
        Config.get().getServiceNaming().update(applicationName);
      }
    }
  }

  public static class EnvironmentReadyV2Advice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterEnvironmentPostProcessed(
        @Advice.Argument(1) final ConfigurableEnvironment environment) {
      if (environment == null || !Config.get().getServiceNaming().isMutable()) {
        return;
      }
      final String applicationName = environment.getProperty("spring.application.name");
      if (applicationName != null && !applicationName.isEmpty()) {
        Config.get().getServiceNaming().update(applicationName);
      }
    }
  }
}
