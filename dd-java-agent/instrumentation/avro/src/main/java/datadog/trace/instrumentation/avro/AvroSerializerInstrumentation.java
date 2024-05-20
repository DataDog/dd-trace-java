package datadog.trace.instrumentation.avro;

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
import org.apache.avro.hadoop.io.AvroSerializer;

@AutoService(InstrumenterModule.class)
public final class AvroSerializerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  static final String instrumentationName = "avro";
  static final String TARGET_TYPE = "org.apache.avro.hadoop.io.AvroSerializer";

  public AvroSerializerInstrumentation() {
    super(instrumentationName);
  }

  @Override
  public String instrumentedType() {
    return TARGET_TYPE;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("serialize")).and(takesArguments(1)),
        AvroSerializerInstrumentation.class.getName() + "$SerializeAdvice");
  }

  public static class SerializeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AvroSerializer serializer, @Advice.Thrown final Throwable throwable) {
      AgentSpan span = activeSpan();
      if (span == null) {
        return;
      }
      if (throwable != null) {
        span.addThrowable(
            throwable instanceof ExecutionException ? throwable.getCause() : throwable);
      }

      if (serializer != null) {
        SchemaExtractor.attachSchemaOnSpan(
            serializer.getWriterSchema(), span, SchemaExtractor.serialization);
      }
    }
  }
}
