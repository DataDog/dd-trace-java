package datadog.trace.instrumentation.avro;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.avro.generic.GenericDatumReader;

@AutoService(InstrumenterModule.class)
public final class GenericDatumReaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  static final String instrumentationName = "avro";
  static final String TARGET_TYPE = "org.apache.avro.generic.GenericDatumReader";

  public GenericDatumReaderInstrumentation() {
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
        isMethod().and(named("read")),
        GenericDatumReaderInstrumentation.class.getName() + "$GenericDatumReaderAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return TARGET_TYPE;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasSuperType(named(hierarchyMarkerType()));
  }

  public static class GenericDatumReaderAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.This GenericDatumReader reader) {
      AgentSpan span = activeSpan();
      if (span == null) {
        return;
      }
      if (reader != null) {
        SchemaExtractor.attachSchemaOnSpan(
            reader.getSchema(), span, SchemaExtractor.deserialization);
      }
    }
  }
}
