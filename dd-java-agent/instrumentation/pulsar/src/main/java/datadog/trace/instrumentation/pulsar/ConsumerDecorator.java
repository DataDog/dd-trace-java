package datadog.trace.instrumentation.pulsar;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.pulsar.MessageTextMapGetter.GETTER;
import static datadog.trace.instrumentation.pulsar.PulsarRequest.*;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.util.concurrent.CompletableFuture;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Messages;

public class ConsumerDecorator extends BaseDecorator {

  public static final CharSequence PULSAR_NAME = UTF8BytesString.create("queue");
  private static final String TOPIC = "topic";
  private static final String LOCAL_SERVICE_NAME = "pulsar";
  private static final String MESSAGING_ID = "messaging.id";
  private static final String MESSAGING_SYSTEM = "messaging.system";

  ConsumerDecorator(){}

  @Override
  protected String[] instrumentationNames() {
    return new String[]{"pulsar","pulsar-client"};
  }

  @Override
  protected CharSequence spanType() {
    return PULSAR_NAME;
  }

  @Override
  protected CharSequence component() {
    return null;
  }

  public static void startAndEnd(PulsarRequest pr,Throwable throwable, Consumer<?> consumer) {
    ContextStore<Consumer, String> contextStore =
        InstrumentationContext.get(Consumer.class, String.class);
    String brokerUrl = contextStore.get(consumer);

    AgentSpan.Context parentContext = propagate().extract(pr, GETTER);
    String topic  = pr.getMessage().getTopicName();
    UTF8BytesString spanName = UTF8BytesString.create(topic + " receive");
    final AgentSpan span = startSpan(spanName,parentContext);
    span.setResourceName(spanName);
    span.setTag(TOPIC , pr.getMessage().getTopicName());
    span.setTag("destination",pr.getDestination());
    span.setTag("broker_url",brokerUrl);
    span.setTag(MESSAGING_SYSTEM,LOCAL_SERVICE_NAME);
    span.setSpanType(PULSAR_NAME);
    span.setTag(MESSAGING_ID,pr.getMessage().getMessageId());
    span.setServiceName(LOCAL_SERVICE_NAME);
    if (throwable != null){
      span.setError(true);
      span.setErrorMessage(throwable.getMessage());
    }

    AgentScope scope = activateSpan(span);

   // beforeFinish(scope);
    scope.span().finish();
    scope.close();
  }

  public static CompletableFuture<Message<?>> wrap(CompletableFuture<Message<?>> future, Consumer<?> consumer) {

    CompletableFuture<Message<?>> result = new CompletableFuture<>();
    future.whenComplete(
        (message, throwable) -> {
          // consumer 用来获取 url
           startAndEnd(create(message) , throwable,consumer);
          runWithContext(
              () -> {
                if (throwable != null) {
                  result.completeExceptionally(throwable);
                } else {
                  result.complete(message);
                }
              });
        }
    );

    return result;
  }

  public static CompletableFuture<Messages<?>> wrapBatch(CompletableFuture<Messages<?>> future, Consumer<?> consumer) {
    CompletableFuture<Messages<?>> result = new CompletableFuture<>();
    future.whenComplete(
        (messages, throwable) -> {
          Message message = null;
          for (Message m: messages) {
            if(m !=null) {
              message = m;
              return;}
          }
          startAndEnd(create(message), throwable,consumer);
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

  private static void runWithContext( Runnable runnable) {
    runnable.run();
  }
}
