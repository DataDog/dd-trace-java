package datadog.trace.instrumentation.jackson.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.*;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.Json2_16ParserHelper;
import com.fasterxml.jackson.core.json.UTF8StreamJsonParser;
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

@AutoService(InstrumenterModule.class)
public class Json2_16ParserInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy {

  static final String TARGET_TYPE = "com.fasterxml.jackson.core.JsonParser";
  static final ElementMatcher.Junction<ClassLoader> VERSION_POST_2_16_0 =
      hasClassNamed("com.fasterxml.jackson.core.StreamWriteConstraints");

  public Json2_16ParserInstrumentation() {
    super("jackson", "jackson-2_16");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    final String className = Json2_16ParserInstrumentation.class.getName();
    transformer.applyAdvice(
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
    return declaresMethod(namedOneOf("getCurrentName", "nextFieldName"))
        .and(
            extendsClass(named(hierarchyMarkerType()))
                .and(namedNoneOf("com.fasterxml.jackson.core.base.ParserMinimalBase")));
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return VERSION_POST_2_16_0;
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(TARGET_TYPE, "datadog.trace.bootstrap.instrumentation.iast.NamedContext");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "com.fasterxml.jackson.core.json" + ".Json2_16ParserHelper",
      "com.fasterxml.jackson.core.sym" + ".ByteQuadsCanonicalizer2_16Helper",
    };
  }

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
        if (jsonParser instanceof UTF8StreamJsonParser
            && Json2_16ParserHelper.fetchInterner((UTF8StreamJsonParser) jsonParser)) {
          context.setCurrentName(result);
          return;
        }
        context.taintName(result);
      }
    }
  }
}
