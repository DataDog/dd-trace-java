package datadog.trace.instrumentation.wildfly;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.jboss.as.ee.component.EnvEntryInjectionSource;

@AutoService(InstrumenterModule.class)
public class EnvEntryInjectionSourceInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public EnvEntryInjectionSourceInstrumentation() {
    super("wildfly", "jee-env-entry");
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.as.ee.component.EnvEntryInjectionSource";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.jboss.as.ee.component.EnvEntryInjectionSource", Object.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$ConstructorAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This final EnvEntryInjectionSource self, @Advice.Argument(0) final Object value) {
      InstrumentationContext.get(EnvEntryInjectionSource.class, Object.class).put(self, value);
    }
  }
}
