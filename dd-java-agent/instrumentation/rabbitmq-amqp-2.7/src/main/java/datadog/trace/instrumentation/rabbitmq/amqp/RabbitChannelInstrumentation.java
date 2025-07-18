package datadog.trace.instrumentation.rabbitmq.amqp;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.CLIENT_DECORATE;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.OPERATION_AMQP_COMMAND;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.OPERATION_AMQP_OUTBOUND;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.TIME_IN_QUEUE_ENABLED;
import static datadog.trace.instrumentation.rabbitmq.amqp.TextMapInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.canThrow;
import static net.bytebuddy.matcher.ElementMatchers.isGetter;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isSetter;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.MessageProperties;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class RabbitChannelInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public RabbitChannelInstrumentation() {
    super("amqp", "rabbitmq");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.rabbitmq.client.Channel";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // Class is added to ignores trie, but it's not final so just being safe
        .and(not(extendsClass(named("reactor.rabbitmq.ChannelProxy"))));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RabbitDecorator",
      packageName + ".TextMapInjectAdapter",
      packageName + ".TracedDelegatingConsumer",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // We want the advice applied in a specific order.
    transformer.applyAdvice(
        isMethod()
            .and(
                not(
                    isGetter()
                        .or(isSetter())
                        .or(nameEndsWith("Listener"))
                        .or(nameEndsWith("Listeners"))
                        .or(
                            namedOneOf(
                                "processAsync",
                                "open",
                                "close",
                                "abort",
                                "basicGet",
                                "basicPublish"))))
            .and(isPublic())
            .and(canThrow(IOException.class).or(canThrow(InterruptedException.class))),
        RabbitChannelInstrumentation.class.getName() + "$ChannelMethodAdvice");
    transformer.applyAdvice(
        isMethod().and(named("basicPublish")).and(takesArguments(6)),
        RabbitChannelInstrumentation.class.getName() + "$ChannelPublishAdvice");
    transformer.applyAdvice(
        isMethod().and(named("basicGet")).and(takesArgument(0, String.class)),
        RabbitChannelInstrumentation.class.getName() + "$ChannelGetAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("basicConsume"))
            .and(takesArgument(0, String.class))
            .and(takesArgument(6, named("com.rabbitmq.client.Consumer"))),
        RabbitChannelInstrumentation.class.getName() + "$ChannelConsumeAdvice");
  }

  public static class ChannelMethodAdvice {
    @Advice.OnMethodEnter
    public static AgentScope onEnter(
        @Advice.This final Channel channel, @Advice.Origin("Channel.#m") final String method) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Channel.class);
      if (callDepth > 0) {
        return null;
      }

      final Connection connection = channel.getConnection();

      final AgentSpan span = startSpan(OPERATION_AMQP_COMMAND);
      span.setResourceName(method);
      CLIENT_DECORATE.setPeerPort(span, connection.getPort());
      CLIENT_DECORATE.afterStart(span);
      CLIENT_DECORATE.onPeerConnection(span, connection.getAddress());
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      CLIENT_DECORATE.onError(scope, throwable);
      CLIENT_DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(Channel.class);
    }
  }

  public static class ChannelPublishAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope setResourceNameAddHeaders(
        @Advice.This final Channel channel,
        @Advice.Argument(0) final String exchange,
        @Advice.Argument(1) final String routingKey,
        @Advice.Argument(value = 4, readOnly = false) AMQP.BasicProperties props,
        @Advice.Argument(5) final byte[] body) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Channel.class);
      if (callDepth > 0) {
        return null;
      }

      final Connection connection = channel.getConnection();

      final AgentSpan span = startSpan(OPERATION_AMQP_OUTBOUND);
      PRODUCER_DECORATE.setPeerPort(span, connection.getPort());
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onPeerConnection(span, connection.getAddress());
      PRODUCER_DECORATE.onPublish(span, exchange, routingKey);
      span.setTag("message.size", body == null ? 0 : body.length);

      // This is the internal behavior when props are null.  We're just doing it earlier now.
      if (props == null) {
        props = MessageProperties.MINIMAL_BASIC;
      }
      final Integer deliveryMode = props.getDeliveryMode();
      if (deliveryMode != null) {
        span.setTag("amqp.delivery_mode", deliveryMode);
      }

      Config config = Config.get();
      if (config.isRabbitPropagationEnabled()
          && !config.isRabbitPropagationDisabledForDestination(exchange)) {
        // We need to copy the BasicProperties and provide a header map we can modify
        Map<String, Object> headers = props.getHeaders();
        headers = (headers == null) ? new HashMap<String, Object>() : new HashMap<>(headers);
        if (TIME_IN_QUEUE_ENABLED) {
          RabbitDecorator.injectTimeInQueueStart(headers);
        }
        DataStreamsTags tags =
            DataStreamsTags.createWithExchange(
                "rabbitmq",
                DataStreamsTags.Direction.Outbound,
                exchange,
                routingKey != null && !routingKey.isEmpty());
        DataStreamsContext dsmContext = DataStreamsContext.fromTags(tags);
        defaultPropagator().inject(span.with(dsmContext), headers, SETTER);
        props =
            new AMQP.BasicProperties(
                props.getContentType(),
                props.getContentEncoding(),
                headers,
                props.getDeliveryMode(),
                props.getPriority(),
                props.getCorrelationId(),
                props.getReplyTo(),
                props.getExpiration(),
                props.getMessageId(),
                props.getTimestamp(),
                props.getType(),
                props.getUserId(),
                props.getAppId(),
                props.getClusterId());
      }
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      PRODUCER_DECORATE.onError(scope, throwable);
      PRODUCER_DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(Channel.class);
    }
  }

  public static class ChannelGetAdvice {
    @Advice.OnMethodEnter
    public static long takeTimestamp(
        @Advice.Local("placeholderScope") AgentScope placeholderScope,
        @Advice.Local("callDepth") int callDepth) {

      callDepth = CallDepthThreadLocalMap.incrementCallDepth(Channel.class);
      // Don't want RabbitCommandInstrumentation to mess up our actual parent span.
      placeholderScope = activateSpan(noopSpan());
      return System.currentTimeMillis();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void extractAndStartSpan(
        @Advice.This final Channel channel,
        @Advice.Argument(0) final String queue,
        @Advice.Enter final long spanStartMillis,
        @Advice.Local("placeholderScope") final AgentScope placeholderScope,
        @Advice.Local("callDepth") final int callDepth,
        @Advice.Return final GetResponse response,
        @Advice.Thrown final Throwable throwable) {
      placeholderScope.close(); // noop span, so no need to finish.
      if (callDepth > 0) {
        return;
      }
      final Connection connection = channel.getConnection();
      final Config config = Config.get();
      final boolean propagate =
          config.isRabbitPropagationEnabled()
              && !config.isRabbitPropagationDisabledForDestination(queue);
      final AgentScope scope =
          RabbitDecorator.startReceivingSpan(
              propagate,
              spanStartMillis,
              null != response ? response.getProps() : null,
              null != response ? response.getBody() : null,
              queue);
      final AgentSpan span = scope.span();
      CONSUMER_DECORATE.setPeerPort(span, connection.getPort());
      CONSUMER_DECORATE.onGet(span, queue);
      CONSUMER_DECORATE.onPeerConnection(span, connection.getAddress());
      CONSUMER_DECORATE.onError(span, throwable);
      CONSUMER_DECORATE.beforeFinish(span);
      RabbitDecorator.finishReceivingSpan(scope);
      CallDepthThreadLocalMap.reset(Channel.class);
    }
  }

  public static class ChannelConsumeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapConsumer(
        @Advice.Argument(0) final String queue,
        @Advice.Argument(value = 6, readOnly = false) Consumer consumer) {
      // We have to save off the queue name here because it isn't available to the consumer later.
      if (consumer != null && !(consumer instanceof TracedDelegatingConsumer)) {
        consumer = CLIENT_DECORATE.wrapConsumer(queue, consumer);
      }
    }
  }
}
