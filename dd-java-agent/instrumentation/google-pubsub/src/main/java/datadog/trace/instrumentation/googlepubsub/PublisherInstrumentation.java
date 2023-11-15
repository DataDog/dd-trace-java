package datadog.trace.instrumentation.googlepubsub;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.instrumentation.googlepubsub.PubSubDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.googlepubsub.PubSubDecorator.PUBSUB_PRODUCE;
import static datadog.trace.instrumentation.googlepubsub.TextMapInjectAdapter.SETTER;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class PublisherInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType, ExcludeFilterProvider {

  public PublisherInstrumentation() {
    super("google-pubsub");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PubSubDecorator",
      packageName + ".PubSubDecorator$RegexExtractor",
      packageName + ".TextMapInjectAdapter",
      packageName + ".TextMapExtractAdapter",
    };
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(RUNNABLE, singletonList("com.google.api.gax.rpc.Watchdog"));
  }

  @Override
  public String instrumentedType() {
    return "com.google.cloud.pubsub.v1.Publisher";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isMethod().and(named("publish")), getClass().getName() + "$Wrap");
  }

  public static final class Wrap {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.Argument(value = 0, readOnly = false) PubsubMessage msg,
        @Advice.This Publisher publisher) {
      final AgentSpan span = startSpan(PUBSUB_PRODUCE);

      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, msg, publisher.getTopicNameString());

      PubsubMessage.Builder builder = msg.toBuilder();
      propagate().inject(span, builder, SETTER);
      msg = builder.build();
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      PRODUCER_DECORATE.onError(scope, throwable);
      PRODUCER_DECORATE.beforeFinish(scope);
      scope.span().finish();
      scope.close();
    }
  }
}
