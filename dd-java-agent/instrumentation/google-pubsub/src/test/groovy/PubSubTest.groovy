import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.FixedTransportChannelProvider
import com.google.api.gax.rpc.TransportChannelProvider
import com.google.cloud.pubsub.v1.AckReplyConsumer
import com.google.cloud.pubsub.v1.AckReplyConsumerWithResponse
import com.google.cloud.pubsub.v1.MessageReceiver
import com.google.cloud.pubsub.v1.MessageReceiverWithAckResponse
import com.google.cloud.pubsub.v1.Publisher
import com.google.cloud.pubsub.v1.Subscriber
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings
import com.google.cloud.pubsub.v1.TopicAdminClient
import com.google.cloud.pubsub.v1.TopicAdminSettings
import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage
import com.google.pubsub.v1.PushConfig
import com.google.pubsub.v1.SubscriptionName
import com.google.pubsub.v1.TopicName
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.grpc.client.GrpcClientDecorator
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.testcontainers.containers.PubSubEmulatorContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Shared

import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.function.Function
import java.util.function.ToDoubleFunction
import java.util.function.ToIntFunction
import java.util.function.ToLongFunction

abstract class PubSubTest extends VersionedNamingTestBase {
  private static final String PROJECT_ID = "dd-trace-java"

  private static final String TOPIC_ID = "test-topic"

  private static final String SUBSCRIPTION_ID = "my-subscription"

  @Shared
  PubSubEmulatorContainer emulator

  @Shared
  ManagedChannel channel

  @Shared
  TransportChannelProvider transportChannelProvider

  @Shared
  def noCredentialsProvider = NoCredentialsProvider.create()

  @Shared
  String subscriptionName

  @Override
  String operation() {
    //specialized methods below
    null
  }

  @Override
  boolean useStrictTraceWrites() {
    false
  }

  boolean shadowGrpcSpans() {
    true
  }

  abstract String operationForConsumer()

  abstract String operationForProducer()

  Object createMessageReceiver(final CountDownLatch latch) {
    return new MessageReceiver() {
        @Override
        void receiveMessage(PubsubMessage message, AckReplyConsumer consumer) {
          consumer.ack()
          latch.countDown()
        }
      }
  }

  def setupSpec() {
    emulator = new PubSubEmulatorContainer(DockerImageName.parse("gcr.io/google.com/cloudsdktool/cloud-sdk:emulators"))
    emulator.start()
    channel = ManagedChannelBuilder.forTarget(emulator.getEmulatorEndpoint()).usePlaintext().build()
    transportChannelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
    createTopic(TOPIC_ID)
    subscriptionName = createSubscription(SUBSCRIPTION_ID, TOPIC_ID).getName()
  }

  def cleanupSpec() {
    channel.shutdown()
    emulator.stop()
  }

  def createTopic(String topicId) throws IOException {
    TopicAdminSettings topicAdminSettings = TopicAdminSettings
    .newBuilder()
    .setTransportChannelProvider(transportChannelProvider)
    .setCredentialsProvider(noCredentialsProvider)
    .build()
    try (TopicAdminClient topicAdminClient = TopicAdminClient.create(topicAdminSettings)) {
      TopicName topicName = TopicName.of(PROJECT_ID, topicId)
      topicAdminClient.createTopic(topicName)
    }
  }

  def createSubscription(String subscriptionId, String topicId) throws IOException {
    SubscriptionAdminSettings subscriptionAdminSettings = SubscriptionAdminSettings
    .newBuilder()
    .setTransportChannelProvider(transportChannelProvider)
    .setCredentialsProvider(noCredentialsProvider)
    .build()
    try (final SubscriptionAdminClient subscriptionAdminClient = SubscriptionAdminClient.create(subscriptionAdminSettings)) {
      SubscriptionName subscriptionName = SubscriptionName.of(PROJECT_ID, subscriptionId)
      subscriptionAdminClient.createSubscription(subscriptionName, TopicName.of(PROJECT_ID, topicId), PushConfig.getDefaultInstance(), 100)
    }
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(GeneralConfig.SERVICE_NAME, "A-service")
    injectSysConfig(GeneralConfig.DATA_STREAMS_ENABLED, isDataStreamsEnabled().toString())
    if (!shadowGrpcSpans()) {
      injectSysConfig(TraceInstrumentationConfig.GOOGLE_PUBSUB_IGNORED_GRPC_METHODS, "")
    }
  }

  def "trace is propagated between producer and consumer"() {
    setup:
    def publisher = Publisher
    .newBuilder(TopicName.of(PROJECT_ID, TOPIC_ID))
    .setChannelProvider(transportChannelProvider)
    .setCredentialsProvider(noCredentialsProvider)
    .build()
    def latch = new CountDownLatch(1)


    def subscriber = Subscriber.newBuilder(subscriptionName, createMessageReceiver(latch))
    .setChannelProvider(transportChannelProvider)
    .setCredentialsProvider(noCredentialsProvider)
    .build()
    subscriber.startAsync().awaitRunning()
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      publisher.publish(PubsubMessage.newBuilder().setData(ByteString.copyFrom('sometext', StandardCharsets.UTF_8)).build())
    })
    // wait for messages to be consumed
    latch.await()

    then:
    def sendSpan
    assertTraces(shadowGrpcSpans() ? 2 : 4, [
      compare            : { List<DDSpan> o1, List<DDSpan> o2 ->
        // trace will never be empty
        o1[0].localRootSpan.getTag(Tags.SPAN_KIND) <=> o2[0].localRootSpan.getTag(Tags.SPAN_KIND)
      },
    ] as Comparator) {
      trace(shadowGrpcSpans() ? 2 : 4) {
        sortSpansByStart()
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operationForProducer()
          resourceName "Produce Topic test-topic"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          errored false
          measured true
          childOfPrevious()
          tags {
            "$Tags.COMPONENT" "java-google-pubsub"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTagsNoPeerService()
          }
        }
        // Publish
        if (!shadowGrpcSpans()) {
          grpcSpans(it)
        }
        sendSpan = span(1)
      }
      if (!shadowGrpcSpans()) {
        // Acknowledge
        trace(2) {
          grpcSpans(it, "A-service", true)
        }
        // ModifyAckDeadline
        trace(2) {
          grpcSpans(it, "A-service", true)
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operationForConsumer()
          resourceName "Consume Subscription my-subscription"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          topLevel true
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "java-google-pubsub"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    publisher.shutdown()
    subscriber.stopAsync().awaitTerminated()
  }


  def grpcSpans(TraceAssert traceAssert, String service = service(), boolean traceRoot = false) {
    traceAssert.span {
      serviceName service
      operationName GrpcClientDecorator.OPERATION_NAME.toString()
      resourceName { true }
      spanType DDSpanTypes.RPC
      errored false
      measured true
      if (traceRoot) {
        topLevel true
      } else {
        childOfPrevious()
      }
      tags {
        "$Tags.COMPONENT" "grpc-client"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "request.type" { String }
        "response.type" { String }
        "$Tags.RPC_SERVICE" { String }
        "status.code" { String }
        if ({ isDataStreamsEnabled() }) {
          "$DDTags.PATHWAY_HASH" { String }
        }
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" { Integer }
        peerServiceFrom(Tags.RPC_SERVICE)
        defaultTags()
      }
    }
    traceAssert.span {
      serviceName service
      operationName "grpc.message"
      resourceName "grpc.message"
      spanType DDSpanTypes.RPC
      errored false
      measured true
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" "grpc-client"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "message.type" { String }
        defaultTagsNoPeerService()
      }
    }
  }
}

class PubSubNamingV0Test extends PubSubTest {
  @Override
  String operationForConsumer() {
    "google-pubsub.consume"
  }

  @Override
  String operationForProducer() {
    "google-pubsub.produce"
  }

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    "google-pubsub"
  }
}


class PubSubNamingV1ForkedTest extends PubSubTest {
  @Override
  String operationForConsumer() {
    "gcp.pubsub.process"
  }

  @Override
  String operationForProducer() {
    "gcp.pubsub.send"
  }

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    "A-service"
  }
}

class PubSubLogGrpcSpansForkedTest extends PubSubNamingV0Test {
  @Override
  boolean shadowGrpcSpans() {
    false
  }
}

class PubSubMessageReceiverWithAckResponseTest extends PubSubNamingV0Test {
  @Override
  Object createMessageReceiver(CountDownLatch latch) {
    new MessageReceiverWithAckResponse() {
      @Override
      void receiveMessage(PubsubMessage message, AckReplyConsumerWithResponse consumer) {
        consumer.ack().get()
        latch.countDown()
      }
    }
  }
}
