package datadog.trace.instrumentation.avro;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.ExecutionException;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.avro.hadoop.io.AvroDeserializer;

@AutoService(InstrumenterModule.class)
public final class AvroDeserializerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  static final String instrumentationName = "avro";
  static final String TARGET_TYPE = "org.apache.avro.hadoop.io.AvroDeserializer";

  public AvroDeserializerInstrumentation() {
    super(instrumentationName);
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
        isMethod().and(named("deserialize").and(takesArguments(1))),
        AvroDeserializerInstrumentation.class.getName() + "$DeserializeAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return TARGET_TYPE;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  public static class DeserializeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AvroDeserializer deserializer,
        @Advice.Thrown final Throwable throwable) {
      AgentSpan span = activeSpan();
      if (span == null) {
        return;
      }
      if (throwable != null) {
        span.addThrowable(
            throwable instanceof ExecutionException ? throwable.getCause() : throwable);
      }
      if (deserializer != null) {
        SchemaExtractor.attachSchemaOnSpan(
            deserializer.getWriterSchema(), span, SchemaExtractor.deserialization);
      }
    }
  }
}
