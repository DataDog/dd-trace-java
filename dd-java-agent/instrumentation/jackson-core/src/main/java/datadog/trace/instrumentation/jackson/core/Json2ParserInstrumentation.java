package datadog.trace.instrumentation.jackson.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.*;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.Propagation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.iast.NamedContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Json2ParserInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {

  static final String TARGET_TYPE = "com.fasterxml.jackson.core.JsonParser";

  public Json2ParserInstrumentation() {
    super("jackson", "jackson-2");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    final String className = Json2ParserInstrumentation.class.getName();
    transformation.applyAdvice(
        namedOneOf("getText", "getValueAsString")
            .and(isPublic())
            .and(takesNoArguments())
            .and(returns(String.class)),
        className + "$TextAdvice");
    transformation.applyAdvice(
        namedOneOf("getCurrentName", "nextFieldName")
            .and(isPublic())
            .and(takesNoArguments())
            .and(returns(String.class)),
        className + "$NameAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return TARGET_TYPE;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return declaresMethod(
            namedOneOf("getText", "getValueAsString", "getCurrentName", "nextFieldName"))
        .and(
            extendsClass(named(hierarchyMarkerType()))
                .and(namedNoneOf("com.fasterxml.jackson.core.base.ParserMinimalBase")));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(TARGET_TYPE, "datadog.trace.bootstrap.instrumentation.iast.NamedContext");
  }

  public static class TextAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void onExit(@Advice.This JsonParser jsonParser, @Advice.Return String result) {
      if (jsonParser != null && result != null) {
        final ContextStore<JsonParser, NamedContext> store =
            InstrumentationContext.get(JsonParser.class, NamedContext.class);
        final NamedContext context = NamedContext.getOrCreate(store, jsonParser);
        final JsonToken current = jsonParser.getCurrentToken();
        if (current == JsonToken.FIELD_NAME) {
          context.taintName(result);
        } else if (current == JsonToken.VALUE_STRING) {
          context.taintValue(result);
        }
      }
    }
  }

  /**
   * Not all field names are caught by {@link JsonParser#getText()} or {@link
   * JsonParser#getValueAsString()}
   *
   * @see JsonParser#getCurrentName()
   * @see JsonParser#nextFieldName()
   */
  public static class NameAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void onExit(@Advice.This JsonParser jsonParser, @Advice.Return String result) {
      if (jsonParser != null
          && result != null
          && jsonParser.getCurrentToken() == JsonToken.FIELD_NAME) {
        final ContextStore<JsonParser, NamedContext> store =
            InstrumentationContext.get(JsonParser.class, NamedContext.class);
        final NamedContext context = NamedContext.getOrCreate(store, jsonParser);
        context.taintName(result);
      }
    }
  }
}
