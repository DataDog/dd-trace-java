package datadog.trace.instrumentation.java.lang;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.security.ProtectionDomain;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

@AutoService(Instrumenter.class)
public class BuiltInJfrEventInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForTypeHierarchy {

  public BuiltInJfrEventInstrumentation() {
    super("jfr");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesNoArguments()),
        BuiltInJfrEventInstrumentation.class.getName() + "$Construct");
  }

  @Override
  public AdviceTransformer transformer() {
    final String[] contextFields = {"rootSpanId", "localRootSpanId"};
    return new AdviceTransformer() {
      // don't define lambda for early startup
      @Override
      public DynamicType.Builder<?> transform(
          DynamicType.Builder<?> builder,
          TypeDescription typeDescription,
          ClassLoader classLoader,
          JavaModule module,
          ProtectionDomain pd) {
        DynamicType.Builder<?> b = builder;
        for (String field : contextFields) {
          b = b.defineField(field, long.class, Visibility.PUBLIC);
        }
        return b;
      }
    };
  }

  @Override
  protected boolean defaultEnabled() {
    return super.defaultEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "jdk.jfr.events.AbstractJDKEvent";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return not(namedOneOf(
            "jdk.jfr.events.ActiveRecordingEvent", "jdk.jfr.events.ActiveSettingEvent"))
        .and(hasSuperClass(named("jdk.jfr.events.AbstractJDKEvent")));
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static void captureContext(
        @Advice.FieldValue(value = "rootSpanId", readOnly = false) long rootSpanId,
        @Advice.FieldValue(value = "spanId", readOnly = false) long spanId) {
      AgentScope scope = activeScope();
      if (activeScope() != null) {
        AgentSpan span = scope.span();
        AgentSpan root = span.getLocalRootSpan();
        spanId = span.getSpanId();
        rootSpanId = root == null ? span.getSpanId() : root.getSpanId();
      }
    }
  }
}
