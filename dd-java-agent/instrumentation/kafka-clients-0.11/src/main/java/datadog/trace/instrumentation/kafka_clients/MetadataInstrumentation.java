package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_IN;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.GROUP_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_PRODUCE;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.TIME_IN_QUEUE_ENABLED;
import static datadog.trace.instrumentation.kafka_clients.TextMapInjectAdapter.SETTER;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.instrumentation.kafka_clients.TracingIterableDelegator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.internals.Sender;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.requests.MetadataResponse;

@AutoService(Instrumenter.class)
public class MetadataInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public MetadataInstrumentation() {
    super("kafka");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.Metadata";
  }
/*
  @Override
  public String[] helperClassNames() {
    return new String[] {
        "org.apache.kafka.common.requests.MetadataResponse"
    };
  }
*/
  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor(),
        MetadataInstrumentation.class.getName() + "$MetadataConstructorAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("update")),
        MetadataInstrumentation.class.getName() + "$MetadataAdvice");
  }

  public static class $MetadataConstructorAdvice {
      @Advice.OnMethodExit(suppress = Throwable.class)
      public static void captureGroup(
          @Advice.This Metadata metadata, @Advice.Argument(0) long refreshBackoffMs) {
        System.out.println("\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\nMETADATA CONSTRUCTOR\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n");
      }
  }

  public static class MetadataAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 1, readOnly = false) MetadataResponse response) {
      System.out.println("[METADATA UPDATE] ON METHOD ENTER");
      System.out.println(response);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    }
  }
}

