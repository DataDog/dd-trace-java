package datadog.trace.instrumentation.opentracing31;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.ReferenceCreator;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentracing.util.GlobalTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * OT interface is reimplemented here rather than using dd-trace-ot as a dependency to allow muzzle
 * to work correctly since it relies on the {@link ReferenceCreator#REFERENCE_CREATION_PACKAGE}
 * prefix.
 */
@AutoService(InstrumenterModule.class)
public class GlobalTracerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public GlobalTracerInstrumentation() {
    super("opentracing", "opentracing-globaltracer");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Avoid matching OT 0.32+ which has its own instrumentation.
    return not(hasClassNamed("io.opentracing.tag.Tag"));
  }

  @Override
  public String instrumentedType() {
    return "io.opentracing.util.GlobalTracer";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OTTracer",
      packageName + ".OTTracer$OTSpanBuilder",
      packageName + ".OTTextMapSetter",
      packageName + ".OTScopeManager",
      packageName + ".OTScopeManager$OTScope",
      packageName + ".OTScopeManager$FakeScope",
      packageName + ".TypeConverter",
      packageName + ".OTSpan",
      packageName + ".OTSpanContext",
      "datadog.trace.instrumentation.opentracing.LogHandler",
      "datadog.trace.instrumentation.opentracing.DefaultLogHandler",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isTypeInitializer(), GlobalTracerInstrumentation.class.getName() + "$GlobalTracerAdvice");
  }

  public static class GlobalTracerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void registerTracer() {
      if (AgentTracer.isRegistered()) {
        GlobalTracer.register(new OTTracer(AgentTracer.get()));
      }
    }
  }
}
