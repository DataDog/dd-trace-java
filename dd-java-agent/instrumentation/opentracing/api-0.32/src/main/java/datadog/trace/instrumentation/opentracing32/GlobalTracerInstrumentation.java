package datadog.trace.instrumentation.opentracing32;

import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.ReferenceCreator;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentracing.util.GlobalTracer;
import net.bytebuddy.asm.Advice;

/**
 * OT interface is reimplemented here rather than using dd-trace-ot as a dependency to allow muzzle
 * to work correctly since it relies on the {@link ReferenceCreator#REFERENCE_CREATION_PACKAGE}
 * prefix.
 */
@AutoService(Instrumenter.class)
public class GlobalTracerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public GlobalTracerInstrumentation() {
    super("opentracing", "opentracing-globaltracer");
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
      packageName + ".OTTextMapInjectSetter",
      packageName + ".OTScopeManager",
      packageName + ".OTScopeManager$OTScope",
      packageName + ".TypeConverter",
      packageName + ".OTSpan",
      packageName + ".OTSpanContext",
      "datadog.trace.instrumentation.opentracing.LogHandler",
      "datadog.trace.instrumentation.opentracing.DefaultLogHandler",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isTypeInitializer(), GlobalTracerInstrumentation.class.getName() + "$GlobalTracerAdvice");
  }

  public static class GlobalTracerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void registerTracer() {
      if (AgentTracer.isRegistered()) {
        GlobalTracer.registerIfAbsent(new OTTracer(AgentTracer.get()));
      }
    }
  }
}
