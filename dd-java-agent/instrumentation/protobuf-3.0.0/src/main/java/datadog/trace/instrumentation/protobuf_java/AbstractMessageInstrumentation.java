package datadog.trace.instrumentation.protobuf_java;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.protobuf_java.Decorator.SERIALIZER_DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import com.google.protobuf.AbstractMessage;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class AbstractMessageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  static final String instrumentationName = "protobuf";
  static final String TARGET_TYPE = "com.google.protobuf.AbstractMessage";
  static final UTF8BytesString OPERATION = UTF8BytesString.create("protobuf.serialize");

  public AbstractMessageInstrumentation() {
    super(instrumentationName);
  }

  @Override
  public String hierarchyMarkerType() {
    return TARGET_TYPE;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return declaresMethod(named("writeTo"))
        .and(extendsClass(named(hierarchyMarkerType())))
        .and(not(nameStartsWith("com.google.protobuf")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SchemaExtractor", packageName + ".Decorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("writeTo")),
        AbstractMessageInstrumentation.class.getName() + "$WriteToAdvice");
  }

  public static class WriteToAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This AbstractMessage message) {
      final AgentSpan span = startSpan(instrumentationName, OPERATION);
      SERIALIZER_DECORATOR.attachSchemaOnSpan(message, span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      AgentSpan span = scope.span();
      SERIALIZER_DECORATOR.onError(span, throwable);
      scope.close();
      span.finish();
    }
  }
}
