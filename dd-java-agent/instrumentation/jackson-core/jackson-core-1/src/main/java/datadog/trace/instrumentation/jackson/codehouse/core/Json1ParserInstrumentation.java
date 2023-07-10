package datadog.trace.instrumentation.jackson.codehouse.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.codehaus.jackson.JsonParser;

@AutoService(Instrumenter.class)
public class Json1ParserInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {

  public Json1ParserInstrumentation() {
    super("jackson", "jackson-1");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {

    transformation.applyAdvice(
        NameMatchers.<MethodDescription>namedOneOf("getCurrentName", "getText", "nextTextValue")
            .and(isMethod())
            .and(isPublic())
            .and(returns(String.class)),
        Json1ParserInstrumentation.class.getName() + "$JsonParserAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.codehaus.jackson.JsonParser";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(
            named(hierarchyMarkerType())
                .and(namedNoneOf("org.codehaus.jackson.impl.JsonParserMinimalBase")))
        .and(declaresMethod(namedOneOf("getCurrentName", "getText", "nextTextValue")));
  }

  public static class JsonParserAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void onExit(@Advice.This JsonParser jsonParser, @Advice.Return String result) {
      if (jsonParser != null && result != null) {
        final PropagationModule module = InstrumentationBridge.PROPAGATION;
        if (module != null) {
          module.taintIfInputIsTainted(result, jsonParser);
        }
      }
    }
  }
}
