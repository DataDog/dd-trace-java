package datadog.trace.instrumentation.protobuf_java;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.protobuf_java.Decorator.DESERIALIZER_DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.MessageLite;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class AbstractParserInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  static final String instrumentationName = "protobuf";
  static final String TARGET_TYPE = "com.google.protobuf.AbstractParser";
  static final String DESERIALIZE = "deserialize";

  public AbstractParserInstrumentation() {
    super(instrumentationName);
  }

  @Override
  public String hierarchyMarkerType() {
    return TARGET_TYPE;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()))
        .and(not(nameStartsWith("com.google.protobuf")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OpenAPIFormatExtractor", packageName + ".Decorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("parsePartialFrom")),
        AbstractParserInstrumentation.class.getName() + "$ParseFromAdvice");
  }

  public static class ParseFromAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter() {
      final AgentSpan span = startSpan(instrumentationName, DESERIALIZE);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return MessageLite message) {
      if (scope == null) {
        return;
      }
      AgentSpan span = scope.span();
      DESERIALIZER_DECORATOR.onError(span, throwable);
      if (message instanceof AbstractMessage) {
        DESERIALIZER_DECORATOR.attachSchemaOnSpan((AbstractMessage) message, span);
      }
      span.finish();
      scope.close();
    }
  }
}
