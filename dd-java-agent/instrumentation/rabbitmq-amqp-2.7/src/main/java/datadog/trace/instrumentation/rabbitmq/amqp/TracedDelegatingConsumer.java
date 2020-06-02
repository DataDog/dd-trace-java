package datadog.trace.instrumentation.rabbitmq.amqp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.AMQP_COMMAND;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DD_MEASURED;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.MESSAGE_SIZE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SPAN_ORIGIN_TYPE;
import static datadog.trace.instrumentation.rabbitmq.amqp.RabbitDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.rabbitmq.amqp.TextMapExtractAdapter.GETTER;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import java.io.IOException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Wrapping the consumer instead of instrumenting it directly because it doesn't get access to the
 * queue name when the message is consumed.
 */
@Slf4j
public class TracedDelegatingConsumer implements Consumer {
  private final String queue;
  private final Consumer delegate;

  public TracedDelegatingConsumer(final String queue, final Consumer delegate) {
    this.queue = queue;
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
      final Map<String, Object> headers = properties.getHeaders();
      final Context context = headers == null ? null : propagate().extract(headers, GETTER);

      final AgentSpan span =
          startSpan(AMQP_COMMAND, context)
              .setTag(MESSAGE_SIZE, body == null ? 0 : body.length)
              .setTag(SPAN_ORIGIN_TYPE, delegate.getClass().getName())
              .setTag(DD_MEASURED, true);
      CONSUMER_DECORATE.afterStart(span);
      CONSUMER_DECORATE.onDeliver(span, queue, envelope);

      if (properties.getTimestamp() != null) {
        // this will be set if the sender sets the timestamp,
        // or if a plugin is installed on the rabbitmq broker
        long produceTime = properties.getTimestamp().getTime();
        long consumeTime = NANOSECONDS.toMillis(span.getStartTime());
        span.setTag(RECORD_QUEUE_TIME_MS, Math.max(0L, consumeTime - produceTime));
      }

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
          scope.span().finish();
        }
      }
    }
  }
}
