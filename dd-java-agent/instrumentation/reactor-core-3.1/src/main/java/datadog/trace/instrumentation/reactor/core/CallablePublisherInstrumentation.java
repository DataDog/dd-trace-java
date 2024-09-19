package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperClass;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.reactive.PublisherState;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;

@AutoService(InstrumenterModule.class)
public class CallablePublisherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, ExcludeFilterProvider {
  public CallablePublisherInstrumentation() {
    super("reactor-core");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(nameStartsWith("block")), getClass().getName() + "$BlockingAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("org.reactivestreams.Subscriber", PublisherState.class.getName());
    ret.put("org.reactivestreams.Publisher", PublisherState.class.getName());
    return ret;
  }

  @Override
  public String hierarchyMarkerType() {
    return "reactor.core.publisher.Mono";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasSuperClass(namedOneOf("reactor.core.publisher.Mono", "reactor.core.publisher.Flux"));
  }

  public static class BlockingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This final Publisher self,
        @Advice.Local("publisherState") PublisherState publisherState) {
      publisherState =
          InstrumentationContext.get(Publisher.class, PublisherState.class).remove(self);
      if (publisherState == null || publisherState.getSubscriptionSpan() == null) {
        return null;
      }
      return activateSpan(publisherState.getSubscriptionSpan());
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.This final Object self,
        @Advice.Enter final AgentScope scope,
        @Advice.Local("publisherState") final PublisherState state,
        @Advice.Thrown Throwable throwable) {
      if (scope != null) {
        scope.close();
      }
      if (state == null || state.getPartnerSpans() == null) {
        return;
      }
      for (AgentSpan span : state.getPartnerSpans()) {
        if (throwable != null) {
          span.addThrowable(throwable);
        }
        span.finish();
      }
    }
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    // this will loop indefinitely and we do not want to create a continuation for this that will
    // never be canceled
    return Collections.singletonMap(
        ExcludeFilter.ExcludeType.RUNNABLE,
        Arrays.asList(
            "reactor.core.publisher.EventLoopProcessor$RequestTask",
            "reactor.core.publisher.WorkQueueProcessor$WorkQueueInner$1",
            "reactor.core.publisher.WorkQueueProcessor$WorkQueueInner",
            "reactor.core.publisher.EventLoopProcessor",
            "reactor.core.publisher.WorkQueueProcessor",
            "reactor.core.publisher.TopicProcessor$TopicInner$1",
            "reactor.core.publisher.TopicProcessor$TopicInner"));
  }
}
