package datadog.trace.instrumentation.jackson.codehouse.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.Propagation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.iast.NamedContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;

/** TODO: keep a stack like structure pointing to the whole path */
@AutoService(InstrumenterModule.class)
public class Json1ParserInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  static final String JSON_PARSER = "org.codehaus.jackson.JsonParser";

  public Json1ParserInstrumentation() {
    super("jackson", "jackson-1");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    final String className = Json1ParserInstrumentation.class.getName();
    transformer.applyAdvice(
        named("getText").and(isPublic()).and(takesNoArguments()).and(returns(String.class)),
        className + "$GetTextAdvice");
    transformer.applyAdvice(
        named("getCurrentName").and(isPublic()).and(takesNoArguments()).and(returns(String.class)),
        className + "$GetCurrentNameAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return JSON_PARSER;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(
            named(hierarchyMarkerType())
                .and(namedNoneOf("org.codehaus.jackson.impl.JsonParserMinimalBase")))
        .and(declaresMethod(namedOneOf("getText", "getCurrentName")));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(JSON_PARSER, "datadog.trace.bootstrap.instrumentation.iast.NamedContext");
  }

  public static class GetTextAdvice {

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
   * Not all field names are caught by {@link JsonParser#getText()}.
   *
   * @see JsonParser#getCurrentName()
   */
  public static class GetCurrentNameAdvice {

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
