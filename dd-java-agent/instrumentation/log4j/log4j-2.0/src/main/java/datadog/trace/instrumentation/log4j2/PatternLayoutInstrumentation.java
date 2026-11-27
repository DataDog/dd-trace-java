package datadog.trace.instrumentation.log4j2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class PatternLayoutInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public PatternLayoutInstrumentation() {
    super("log4j", "log4j-2");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogPatternInject();
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.logging.log4j.core.layout.PatternLayout",
      "org.apache.logging.log4j.core.pattern.PatternParser"
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".CorrelationIdPatternConverter"};
  }

  @Override
  public String muzzleDirective() {
    return "pattern-layout";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(isDeclaredBy(named("org.apache.logging.log4j.core.layout.PatternLayout")))
            .and(takesArgument(2, String.class)),
        packageName + ".PatternLayoutConstructorAdvice");
    transformer.applyAdvice(
        isConstructor()
            .and(isDeclaredBy(named("org.apache.logging.log4j.core.pattern.PatternParser"))),
        getClass().getName() + "$RegisterConverterAdvice");
  }

  public static class RegisterConverterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.FieldValue("converterRules") Map<String, Class<?>> converters) {
      if (Config.get().isLogPatternInject()) {
        converters.put("ddAgentId", CorrelationIdPatternConverter.class);
      }
    }
  }
}
