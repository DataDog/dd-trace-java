package datadog.trace.instrumentation.jackson.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class JsonSerializerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public JsonSerializerInstrumentation() {
    super("jackson", "jackson-2");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("serialize"))
            .and(takesArguments(Object.class, JsonGenerator.class, SerializerProvider.class)),
        JsonSerializerInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.fasterxml.jackson.databind.JsonSerializer";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  public static class InstrumenterAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) final Object value) {}
  }
}
