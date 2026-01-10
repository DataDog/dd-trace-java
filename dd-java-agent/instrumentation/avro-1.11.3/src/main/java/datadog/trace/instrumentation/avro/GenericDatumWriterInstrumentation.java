package datadog.trace.instrumentation.avro;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.avro.Schema;

public final class GenericDatumWriterInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("write")).and(takesArguments(2)),
        GenericDatumWriterInstrumentation.class.getName() + "$GenericDatumWriterAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.avro.generic.GenericDatumWriter";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  public static class GenericDatumWriterAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.FieldValue("root") Schema root) {
      if (!Config.get().isDataStreamsEnabled()) {
        return;
      }
      AgentSpan span = activeSpan();
      if (span == null) {
        return;
      }

      if (root != null) {
        SchemaExtractor.attachSchemaOnSpan(root, span, SchemaExtractor.SERIALIZATION);
      }
    }
  }
}
