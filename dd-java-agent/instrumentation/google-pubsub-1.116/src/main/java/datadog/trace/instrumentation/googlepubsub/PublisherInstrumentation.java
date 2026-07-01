package datadog.trace.instrumentation.googlepubsub;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.googlepubsub.PubSubDecorator.JAVA_PUBSUB;
import static datadog.trace.instrumentation.googlepubsub.PubSubDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.googlepubsub.PubSubDecorator.PUBSUB_PRODUCE;
import static datadog.trace.instrumentation.googlepubsub.TextMapInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.cloud.pubsub.v1.Publisher;
import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public final class PublisherInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.google.cloud.pubsub.v1.Publisher";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvices(
        isMethod().and(named("publish")),
        getClass().getName() + "$Wrap",
        getClass().getName() + "$ContextPropagationAdvice");
  }

  public static final class Wrap {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.This Publisher publisher) {
      final AgentSpan span = startSpan(JAVA_PUBSUB.toString(), PUBSUB_PRODUCE);

      final CharSequence topicName = PRODUCER_DECORATE.extractTopic(publisher.getTopicNameString());
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, topicName);

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

  @AppliesOn(CONTEXT_TRACKING)
  public static final class ContextPropagationAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 0, readOnly = false) PubsubMessage msg,
        @Advice.This Publisher publisher) {
      AgentSpan span = activeSpan();
      if (span == null) return;
      DataStreamsTags tags =
          create(
              "google-pubsub",
              OUTBOUND,
              PRODUCER_DECORATE.extractTopic(publisher.getTopicNameString()).toString());
      PubsubMessage.Builder builder = msg.toBuilder();
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(tags);
      defaultPropagator().inject(span.with(dsmContext), builder, SETTER);
      msg = builder.build();
    }
  }
}
