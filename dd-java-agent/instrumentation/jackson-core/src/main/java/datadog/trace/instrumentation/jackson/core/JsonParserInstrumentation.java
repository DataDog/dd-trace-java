package datadog.trace.instrumentation.jackson.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.fasterxml.jackson.core.JsonParser;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.iast.InstrumentationBridge;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class JsonParserInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {

  public JsonParserInstrumentation() {
    super("jsonParser");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {

    transformation.applyAdvice(
        NameMatchers.<MethodDescription>namedOneOf(
                "getCurrentName", "getText", "nextFieldName", "nextTextValue")
            .and(isMethod())
            .and(isPublic())
            .and(returns(String.class)),
        JsonParserInstrumentation.class.getName() + "$JsonParserAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.fasterxml.jackson.core.JsonParser";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return declaresMethod(namedOneOf("getCurrentName", "getText", "nextFieldName", "nextTextValue"))
        .and(
            extendsClass(named(hierarchyMarkerType()))
                .and(namedNoneOf("com.fasterxml.jackson.core.base.ParserMinimalBase")));
  }

  public static class JsonParserAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This JsonParser jsonParser, @Advice.Return String result) {
      InstrumentationBridge.JACKSON.onJsonParserGetString(jsonParser, result);
    }
  }
}
