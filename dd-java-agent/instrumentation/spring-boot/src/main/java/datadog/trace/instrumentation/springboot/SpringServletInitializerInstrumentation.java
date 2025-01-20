package datadog.trace.instrumentation.springboot;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * This instrumentation is hooking the spring boot servlet initializer that's the entry point for a
 * war based deployment NB: it could be a ForClassHierarchy but is really unusual to have the method
 * overridden and the base one skipped
 */
@AutoService(InstrumenterModule.class)
public class SpringServletInitializerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public SpringServletInitializerInstrumentation() {
    super("spring-boot");
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
        named("onStartup").and(ElementMatchers.takesArguments(1)),
        getClass().getName() + "$WarDeployedAdvice");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.springframework.boot.web.servlet.support.SpringBootServletInitializer",
      "org.springframework.boot.web.support.SpringBootServletInitializer",
    };
  }

  public static class WarDeployedAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before() {
      DeploymentHelper.runningFromWar = true;
    }
  }
}
