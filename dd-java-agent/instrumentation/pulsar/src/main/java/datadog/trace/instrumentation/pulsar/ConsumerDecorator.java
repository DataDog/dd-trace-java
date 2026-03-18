package datadog.trace.instrumentation.pulsar;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.pulsar.MessageTextMapGetter.GETTER;
import static datadog.trace.instrumentation.pulsar.PulsarRequest.*;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.util.concurrent.CompletableFuture;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConsumerDecorator extends BaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(ConsumerDecorator.class);

  public static final CharSequence PULSAR_NAME = UTF8BytesString.create("queue");
  private static final String TOPIC = "topic";
  private static final String LOCAL_SERVICE_NAME = "pulsar";
  private static final String MESSAGING_ID = "messaging.id";
  private static final String MESSAGING_SYSTEM = "messaging.system";

  ConsumerDecorator() {}

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"pulsar", "pulsar-client"};
  }

  @Override
  protected CharSequence spanType() {
    return PULSAR_NAME;
  }

  @Override
  protected CharSequence component() {
    return null;
  }

  public static void startAndEnd(PulsarRequest pr, Throwable throwable, String brokerUrl) {
    if (log.isDebugEnabled()) {
      log.debug("into startAndEnd");
    }
    AgentSpanContext parentContext = extractContextAndGetSpanContext(pr, GETTER);
    String topic = pr.getMessage().getTopicName();
    UTF8BytesString spanName = UTF8BytesString.create(topic + " receive");
    final AgentSpan span = startSpan(spanName, parentContext);
    span.setResourceName(spanName);
    span.setTag(TOPIC, pr.getMessage().getTopicName());
    span.setTag("destination", pr.getDestination());
    span.setTag("broker_url", brokerUrl);
    span.setTag(MESSAGING_SYSTEM, LOCAL_SERVICE_NAME);
    span.setSpanType(PULSAR_NAME);
    span.setTag(MESSAGING_ID, pr.getMessage().getMessageId());
    span.setServiceName(LOCAL_SERVICE_NAME);
    if (throwable != null) {
      span.setError(true);
      span.setErrorMessage(throwable.getMessage());
    }

    AgentScope scope = activateSpan(span);

   // beforeFinish(scope);
    scope.span().finish();
    scope.close();
    if (log.isDebugEnabled()) {
      log.debug("out startAndEnd");
    }
  }

  public static CompletableFuture<Message<?>> wrap(
      CompletableFuture<Message<?>> future, String brokerUrl) {
    if (log.isDebugEnabled()) {
      log.debug("into wrap");
    }
    CompletableFuture<Message<?>> result = new CompletableFuture<>();
    future.whenComplete(
        (message, throwable) -> {
          // consumer 用来获取 url
          if (message == null) {
            return;
          }
          startAndEnd(create(message), throwable, brokerUrl);
          runWithContext(
              () -> {
                if (throwable != null) {
                  result.completeExceptionally(throwable);
                } else {
                  result.complete(message);
                }
              });
        });
    if (log.isDebugEnabled()) {
      log.debug("out wrap");
    }
    return result;
  }

  public static CompletableFuture<Messages<?>> wrapBatch(
      CompletableFuture<Messages<?>> future, String brokerUrl) {
    CompletableFuture<Messages<?>> result = new CompletableFuture<>();
    future.whenComplete(
        (messages, throwable) -> {
          if (messages == null) {
            return;
          }

          for (Message<?> m : messages) {
            if (m != null) {
              startAndEnd(create(m), throwable, brokerUrl);
            }
          }

          runWithContext(
              () -> {
                if (throwable != null) {
                  result.completeExceptionally(throwable);
                } else {
                  result.complete(messages);
                }
              });
        });

    return result;
  }

  private static void runWithContext(Runnable runnable) {
    runnable.run();
    if (log.isDebugEnabled()) {
      log.debug("out runWithContext");
    }
  }
}
