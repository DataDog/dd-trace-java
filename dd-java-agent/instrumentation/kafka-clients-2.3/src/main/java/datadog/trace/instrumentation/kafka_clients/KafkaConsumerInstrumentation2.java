package datadog.trace.instrumentation.kafka_clients;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.internals.ConsumerCoordinator;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.KafkaMetric;

import java.util.HashMap;
import java.util.Map;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * This instrumentation saves additional information from the KafkaConsumer, such as consumer group
 * and cluster ID, in the context store for later use.
 */
@AutoService(Instrumenter.class)
public final class KafkaConsumerInstrumentation2 extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public KafkaConsumerInstrumentation2() {
    super("kafka");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.consumer.KafkaConsumer";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("poll"))
            .and(takesArguments(1))
            .and(returns(named("org.apache.kafka.clients.consumer.ConsumerRecords"))),
        KafkaConsumerInstrumentation2.class.getName() + "$RecordsAdvice");
  }

  public static class RecordsAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.This KafkaConsumer consumer, @Advice.Return ConsumerRecords records) {
      // go through all consumer metrics and print the value and the metric name that is the key in the map.
      System.out.println("pizza mozzarella");
      for (Map.Entry<MetricName, ? extends Metric> entry : ((Map<MetricName, ? extends Metric>) consumer.metrics()).entrySet()) {
        KafkaMetric m = (KafkaMetric) entry.getValue();
        AgentTracer.get().getStatsDClient().gauge("kafka.test_metric", 42, "test_tag:piotr");
        System.out.println("the metric is " +entry.getKey().name() + entry.getKey().tags() + entry.getKey().group() + " " + m.metricValue().toString());
        System.out.println("tags are: " + entry.getKey().tags().toString());
      }
    }
  }
}
