package datadog.trace.instrumentation.jackson.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.*;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.fasterxml.jackson.core.JsonParser;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Json2ParserInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {

  public Json2ParserInstrumentation() {
    super("jackson", "jackson-2");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {

    transformation.applyAdvice(
        NameMatchers.<MethodDescription>namedOneOf(
                "getCurrentName",
                "getText",
                "nextFieldName",
                "nextTextValue",
                "getValueAsString",
                "nextFieldName",
                "nextTextValue")
            .and(isMethod())
            .and(isPublic())
            .and(returns(String.class)),
        Json2ParserInstrumentation.class.getName() + "$JsonParserAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.fasterxml.jackson.core.JsonParser";
  }

  @Override
  public AdviceTransformer transformer() {
    return new VisitingTransformer(new TaintableVisitor(hierarchyMarkerType()));
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return declaresMethod(
            namedOneOf(
                "getCurrentName",
                "getText",
                "nextFieldName",
                "nextTextValue",
                "getValueAsString",
                "nextFieldName",
                "nextTextValue"))
        .and(
            extendsClass(named(hierarchyMarkerType()))
                .and(namedNoneOf("com.fasterxml.jackson.core.base.ParserMinimalBase")));
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
