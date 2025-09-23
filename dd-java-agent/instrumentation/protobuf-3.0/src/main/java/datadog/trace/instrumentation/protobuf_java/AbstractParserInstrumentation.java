package datadog.trace.instrumentation.protobuf_java;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.AbstractParser;
import com.google.protobuf.MessageLite;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.ExecutionException;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class AbstractParserInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  static final String instrumentationName = "protobuf";
  static final String TARGET_TYPE = "com.google.protobuf.AbstractParser";

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
      packageName + ".SchemaExtractor",
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
    public static void trackDepth() {
      CallDepthThreadLocalMap.incrementCallDepth(AbstractParser.class);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Thrown final Throwable throwable, @Advice.Return MessageLite message) {
      final int callDepth = CallDepthThreadLocalMap.decrementCallDepth(AbstractParser.class);
      if (callDepth > 0) {
        return;
      }
      AgentSpan span = activeSpan();
      if (span == null) {
        return;
      }
      if (throwable != null) {
        span.addThrowable(
            throwable instanceof ExecutionException ? throwable.getCause() : throwable);
      }
      if (message instanceof AbstractMessage) {
        SchemaExtractor.attachSchemaOnSpan(
            ((AbstractMessage) message).getDescriptorForType(),
            span,
            SchemaExtractor.deserialization);
      }
    }
  }
}
