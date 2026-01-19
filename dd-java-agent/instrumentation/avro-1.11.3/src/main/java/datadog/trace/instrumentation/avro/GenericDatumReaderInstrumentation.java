package datadog.trace.instrumentation.avro;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.avro.generic.GenericDatumReader;

public final class GenericDatumReaderInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("read")),
        GenericDatumReaderInstrumentation.class.getName() + "$GenericDatumReaderAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.avro.generic.GenericDatumReader";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  public static class GenericDatumReaderAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.This GenericDatumReader reader) {
      if (!Config.get().isDataStreamsEnabled()) {
        return;
      }
      AgentSpan span = activeSpan();
      if (span == null) {
        return;
      }
      if (reader != null) {
        SchemaExtractor.attachSchemaOnSpan(
            reader.getSchema(), span, SchemaExtractor.DESERIALIZATION);
      }
    }
  }
}
