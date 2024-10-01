package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * This instrumentation is responsible for capturing the right state when Mono or Flux block*
 * methods are called. This because the mechanism they handle this differs a bit of the standard
 * {@link Publisher#subscribe(Subscriber)}
 */
@AutoService(InstrumenterModule.class)
public class BlockingPublisherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public BlockingPublisherInstrumentation() {
    super("reactor-core");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$AsyncExtensionInstallAdvice");
    transformer.applyAdvice(
        isMethod().and(nameStartsWith("block")), getClass().getName() + "$BlockingAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("org.reactivestreams.Publisher", AgentSpan.class.getName());
  }

  @Override
  public String hierarchyMarkerType() {
    return "reactor.core.publisher.Mono";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasSuperType(namedOneOf("reactor.core.publisher.Mono", "reactor.core.publisher.Flux"));
  }

  public static class BlockingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.This final Publisher self) {
      final AgentSpan span = InstrumentationContext.get(Publisher.class, AgentSpan.class).get(self);
      if (span == null || span == activeSpan()) {
        return null;
      }
      return activateSpan(span);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter final AgentScope scope, @Advice.Thrown Throwable throwable) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  public static class AsyncExtensionInstallAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void init() {
      ReactorAsyncResultSupportExtension.initialize();
    }
  }
}
