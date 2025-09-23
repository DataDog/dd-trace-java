package datadog.trace.instrumentation.googlepubsub;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
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
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class PublisherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {

  public PublisherInstrumentation() {
    super("google-pubsub", "google-pubsub-publisher");
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isMethod().and(named("publish")), getClass().getName() + "$Wrap");
  }

  public static final class Wrap {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.Argument(value = 0, readOnly = false) PubsubMessage msg,
        @Advice.This Publisher publisher) {
      final AgentSpan span = startSpan(PUBSUB_PRODUCE);

      final CharSequence topicName = PRODUCER_DECORATE.extractTopic(publisher.getTopicNameString());
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, topicName);

      DataStreamsTags tags = create("google-pubsub", OUTBOUND, topicName.toString());
      PubsubMessage.Builder builder = msg.toBuilder();
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(tags);
      defaultPropagator().inject(span.with(dsmContext), builder, SETTER);
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
