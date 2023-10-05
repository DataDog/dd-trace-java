package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.common.requests.MetadataResponse;

@AutoService(Instrumenter.class)
public class MetadataInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public MetadataInstrumentation() {
    super("kafka");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.kafka.clients.Metadata";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".KafkaDecorator"};
  }

  // TODO figure this out
  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.kafka.clients.Metadata", "java.lang.String");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    System.out.println("[APPLYING TRANSFORMATION]");
    transformation.applyAdvice(
        isConstructor(), MetadataInstrumentation.class.getName() + "$MetadataConstructorAdvice");
    transformation.applyAdvice(
        isMethod().and(named("update")),
        MetadataInstrumentation.class.getName() + "$MetadataUpdateAdvice");
  }

  public static class MetadataUpdateAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This final Metadata metadata, @Advice.Argument(1) final MetadataResponse response) {
      System.out.println("[MetadataUpdateAdvice] enter");
      if (response == null) {
        System.out.println("[MetadataUpdateAdvice] NULL RESPONSE");
      } else {
        System.out.println("[MetadataUpdateAdvice] NON NULL RESPONSE: " + response.clusterId());
        System.out.println("[MetadataUpdateAdvice] NON NULL RESPONSE: " + response.brokersById());
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        System.out.println("[MetadataUpdateAdvice] timestamp: " + dateFormat.format(date));
        InstrumentationContext.get(Metadata.class, String.class)
            .put(metadata, response.clusterId());
      }
      for (MetadataResponse.TopicMetadata topicMetadata : response.topicMetadata()) {
        System.out.println("[MetadataUpdateAdvice] topic: " + topicMetadata.topic());
        for (MetadataResponse.PartitionMetadata partitionMetadata :
            topicMetadata.partitionMetadata()) {
          System.out.println(
              "[MetadataUpdateAdvice]   partition: " + partitionMetadata.partition());
          System.out.println(
              "[MetadataUpdateAdvice]   in sync repliacas: " + partitionMetadata.replicaIds);
          System.out.println(
              "[MetadataUpdateAdvice]   in sync repliacas: " + partitionMetadata.inSyncReplicaIds);
          System.out.println(
              "[MetadataUpdateAdvice]   in sync repliacas: " + partitionMetadata.offlineReplicaIds);
          System.out.println("[MetadataUpdateAdvice]   leader: " + partitionMetadata.leaderId);
          System.out.println(
              "[MetadataUpdateAdvice]   offline replicas: " + partitionMetadata.topicPartition);
          System.out.println("[MetadataUpdateAdvice] ============");
        }
      }
    }

    @Advice.OnMethodExit
    public static void extractAndStartSpan() {
      System.out.println("[MetadataUpdateAdvice] exit");
    }
  }

  public static class MetadataConstructorAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static int getCallDepth() {
      System.out.println("[MetadataInstrumentation] enter");
      return CallDepthThreadLocalMap.incrementCallDepth(Metadata.class);
    }

    @Advice.OnMethodExit
    public static void setResourceNameAddHeaders(
        @Advice.This final Metadata command, @Advice.Enter final int callDepth) {
      System.out.println("[MetadataInstrumentation] exit");
    }

    /**
     * This instrumentation will match with 2.6, but the channel instrumentation only matches with
     * 2.7 because of TracedDelegatingConsumer. This unused method is added to ensure consistent
     * muzzle validation by preventing match with 2.6.
     */
    // public static void muzzleCheck(final TracedDelegatingConsumer consumer) {
    //  consumer.handleRecoverOk(null);
    // }
  }
}
