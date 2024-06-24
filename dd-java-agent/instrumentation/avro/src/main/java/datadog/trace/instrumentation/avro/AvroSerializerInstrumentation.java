package datadog.trace.instrumentation.avro;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.avro.Schema;

@AutoService(InstrumenterModule.class)
public final class AvroSerializerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  static final String instrumentationName = "avro";
  static final String TARGET_TYPE = "org.apache.avro.io.DatumReader";

  public AvroSerializerInstrumentation() {
    super(instrumentationName);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SchemaExtractor", "datadog.trace.instrumentation.avro.SchemaExtractor$1",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("setSchema").and(takesArguments(1))),
        AvroSerializerInstrumentation.class.getName() + "$SerializeAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return TARGET_TYPE;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  public static class SerializeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.Argument(0) final Schema schema) {
      AgentSpan span = activeSpan();
      if (span == null) {
        return;
      }
      if (schema != null) {
        System.out.println("Attaching schema to span:" + schema.toString());
        SchemaExtractor.attachSchemaOnSpan(schema, span, SchemaExtractor.serialization);
      }
    }
  }
}
