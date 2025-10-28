package datadog.trace.instrumentation.tomcat;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.ProcessTags;
import net.bytebuddy.asm.Advice;
import org.apache.catalina.core.ContainerBase;
import org.apache.catalina.core.StandardEngine;

@AutoService(InstrumenterModule.class)
public class ContainerBaseInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ContainerBaseInstrumentation() {
    super("tomcat");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.catalina.core.ContainerBase";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("setName")), getClass().getName() + "$SetNameAdvice");
  }

  @Override
  public String muzzleDirective() {
    return "tomcat-processtags";
  }

  public static class SetNameAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterSetName(@Advice.This final ContainerBase engine) {
      if (engine instanceof StandardEngine) {
        String engineName = engine.getName();
        if (engineName != null) {
          ProcessTags.addTag(ProcessTags.SERVER_NAME, engineName);
          ProcessTags.addTag(ProcessTags.SERVER_TYPE, "tomcat");
        }
      }
    }
  }
}
