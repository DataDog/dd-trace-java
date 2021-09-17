package datadog.trace.instrumentation.rabbitmq.amqp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.CONSUMER_DECORATE;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapping the consumer instead of instrumenting it directly because it doesn't get access to the
 * queue name when the message is consumed.
 */
public class TracedDelegatingConsumer implements Consumer {

  private static final Logger log = LoggerFactory.getLogger(TracedDelegatingConsumer.class);
  private final String queue;
  private final Consumer delegate;
  private final boolean propagate;

  public TracedDelegatingConsumer(final String queue, final Consumer delegate) {
    this.queue = queue;
    this.propagate =
        Config.get().isRabbitPropagationEnabled()
            && !Config.get().isRabbitPropagationDisabledForDestination(queue);
    this.delegate = delegate;
  }

  @Override
  public void handleConsumeOk(final String consumerTag) {
    delegate.handleConsumeOk(consumerTag);
  }

  @Override
  public void handleCancelOk(final String consumerTag) {
    delegate.handleCancelOk(consumerTag);
  }

  @Override
  public void handleCancel(final String consumerTag) throws IOException {
    delegate.handleCancel(consumerTag);
  }

  @Override
  public void handleShutdownSignal(final String consumerTag, final ShutdownSignalException sig) {
    delegate.handleShutdownSignal(consumerTag, sig);
  }

  @Override
  public void handleRecoverOk(final String consumerTag) {
    delegate.handleRecoverOk(consumerTag);
  }

  @Override
  public void handleDelivery(
      final String consumerTag,
      final Envelope envelope,
      final AMQP.BasicProperties properties,
      final byte[] body)
      throws IOException {
    AgentScope scope = null;
    try {
      scope = RabbitDecorator.startReceivingSpan(propagate, 0, properties, body, queue);
      AgentSpan span = scope.span();
      CONSUMER_DECORATE.onDeliver(span, queue, envelope);
      scope = activateSpan(span);
    } catch (final Exception e) {
      log.debug("Instrumentation error in tracing consumer", e);
    } finally {
      try {
        // Call delegate.
        delegate.handleDelivery(consumerTag, envelope, properties, body);
      } catch (final Throwable throwable) {
        if (scope != null) {
          CONSUMER_DECORATE.onError(scope, throwable);
        }
        throw throwable;
      } finally {
        if (scope != null) {
          CONSUMER_DECORATE.beforeFinish(scope);
          scope.close();
          RabbitDecorator.finishReceivingSpan(scope);
        }
      }
    }
  }
}
