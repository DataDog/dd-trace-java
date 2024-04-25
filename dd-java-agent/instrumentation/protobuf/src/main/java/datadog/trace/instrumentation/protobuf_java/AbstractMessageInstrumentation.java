package datadog.trace.instrumentation.protobuf_java;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import com.google.protobuf.AbstractMessage;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.ExecutionException;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class AbstractMessageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  static final String instrumentationName = "protobuf";
  static final String TARGET_TYPE = "com.google.protobuf.AbstractMessage";

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
      packageName + ".SchemaExtractor",
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
    public static void onEnter(@Advice.This AbstractMessage message) {
      SchemaExtractor.attachSchemaOnSpan(message, activeSpan(), SchemaExtractor.serialization);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.Thrown final Throwable throwable) {
      AgentSpan span = activeSpan();
      if (throwable != null) {
        span.addThrowable(
            throwable instanceof ExecutionException ? throwable.getCause() : throwable);
      }
    }
  }
}
