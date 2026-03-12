package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.api.datastreams.DataStreamsTags.createWithDataset;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.DDTags;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import datadog.trace.payloadtags.PayloadTagsData;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;

public class AwsSdkClientDecorator extends HttpClientDecorator<SdkHttpRequest, SdkHttpResponse>
    implements CarrierSetter<SdkHttpRequest.Builder> {
  public static final AwsSdkClientDecorator DECORATE = new AwsSdkClientDecorator();
  private static final DDCache<String, CharSequence> CACHE =
      DDCaches.newFixedSizeCache(128); // cloud services can have high cardinality

  static final CharSequence COMPONENT_NAME = UTF8BytesString.create("java-aws-sdk");

  // We only want tag interceptor to take priority
  private static final byte RESOURCE_NAME_PRIORITY = ResourceNamePriorities.TAG_INTERCEPTOR - 1;

  public static final boolean AWS_LEGACY_TRACING = Config.get().isAwsLegacyTracingEnabled();

  public static final boolean SQS_LEGACY_TRACING = Config.get().isSqsLegacyTracingEnabled();

  private static final String SQS_SERVICE_NAME =
      AWS_LEGACY_TRACING || SQS_LEGACY_TRACING ? "sqs" : Config.get().getServiceName();

  private static final String SNS_SERVICE_NAME =
      SpanNaming.instance().namingSchema().cloud().serviceForRequest("aws", "sns");
  private static final String GENERIC_SERVICE_NAME =
      SpanNaming.instance().namingSchema().cloud().serviceForRequest("aws", null);

  private static final Set<String> KINESIS_PUT_RECORD_OPERATION_NAMES;

  static {
    KINESIS_PUT_RECORD_OPERATION_NAMES = new HashSet<>();
    KINESIS_PUT_RECORD_OPERATION_NAMES.add("PutRecord");
    KINESIS_PUT_RECORD_OPERATION_NAMES.add("PutRecords");
  }

  private static final Set<String> SNS_PUBLISH_OPERATION_NAMES;

  static {
    SNS_PUBLISH_OPERATION_NAMES = new HashSet<>();
    SNS_PUBLISH_OPERATION_NAMES.add("Publish");
    SNS_PUBLISH_OPERATION_NAMES.add("PublishBatch");
  }

  public static final ExecutionAttribute<String> KINESIS_STREAM_ARN_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("KinesisStreamArn", () -> new ExecutionAttribute<>("KinesisStreamArn"));

  // not static because this object would be ClassLoader specific if multiple SDK instances were
  // loaded by different loaders
  private SdkField<Instant> kinesisApproximateArrivalTimestampField = null;

  public CharSequence spanName(final ExecutionAttributes attributes) {
    final String awsServiceName = attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME);
    final String awsOperationName = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);

    final String qualifiedName = awsServiceName + "." + awsOperationName;

    return CACHE.computeIfAbsent(
        qualifiedName,
        s ->
            SpanNaming.instance()
                .namingSchema()
                .cloud()
                .operationForRequest(
                    "aws", attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME), s));
  }

  public Context onSdkRequest(
      final Context context,
      final SdkRequest request,
      final SdkHttpRequest httpRequest,
      final ExecutionAttributes attributes) {
    final AgentSpan span = fromContext(context);
    final String awsServiceName = attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME);
    final String awsOperationName = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
    onOperation(span, awsServiceName, awsOperationName);

    Config config = Config.get();
    if (config.isCloudRequestPayloadTaggingEnabled()
        && config.isCloudPayloadTaggingEnabledFor(awsServiceName)) {
      awsPojoToTags(span, ConfigDefaults.DEFAULT_TRACE_CLOUD_PAYLOAD_REQUEST_TAG, request);
    }

    // S3
    request.getValueForField("Bucket", String.class).ifPresent(name -> setBucketName(span, name));
    if ("s3".equalsIgnoreCase(awsServiceName) && traceConfig().isDataStreamsEnabled()) {
      request
          .getValueForField("Key", String.class)
          .ifPresent(key -> span.setTag(InstrumentationTags.AWS_OBJECT_KEY, key));
      span.setTag(Tags.HTTP_REQUEST_CONTENT_LENGTH, getRequestContentLength(httpRequest));
    }

    getRequestKey(request).ifPresent(key -> setObjectKey(span, key));
    request
        .getValueForField("StorageClass", String.class)
        .ifPresent(
            storageClass -> span.setTag(InstrumentationTags.AWS_STORAGE_CLASS, storageClass));

    // SQS
    request
        .getValueForField("QueueUrl", String.class)
        .ifPresent(
            url -> {
              span.setTag(InstrumentationTags.AWS_QUEUE_URL, url);
              setPeerService(span, InstrumentationTags.AWS_QUEUE_URL, url);
            });
    request.getValueForField("QueueName", String.class).ifPresent(name -> setQueueName(span, name));

    // SNS
    Optional<String> snsTopicArn = request.getValueForField("TopicArn", String.class);
    if (!snsTopicArn.isPresent()) {
      snsTopicArn = request.getValueForField("TargetArn", String.class);
    }
    Optional<String> snsTopicName = snsTopicArn.map(arn -> arn.substring(arn.lastIndexOf(':') + 1));
    snsTopicName.ifPresent(topic -> setTopicName(span, topic));

    // Kinesis
    request
        .getValueForField("StreamName", String.class)
        .ifPresent(name -> setStreamName(span, name));
    Optional<String> kinesisStreamArn = request.getValueForField("StreamARN", String.class);
    kinesisStreamArn.ifPresent(
        streamArn -> {
          if (traceConfig().isDataStreamsEnabled()) {
            attributes.putAttribute(KINESIS_STREAM_ARN_ATTRIBUTE, streamArn);
          }
          int streamNameStart = streamArn.indexOf(":stream/");
          if (streamNameStart >= 0) {
            setStreamName(span, streamArn.substring(streamNameStart + 8));
          }
        });

    // DynamoDB
    request.getValueForField("TableName", String.class).ifPresent(name -> setTableName(span, name));

    // DSM
    if (traceConfig().isDataStreamsEnabled()) {
      if (kinesisStreamArn.isPresent()
          && "kinesis".equalsIgnoreCase(awsServiceName)
          && KINESIS_PUT_RECORD_OPERATION_NAMES.contains(awsOperationName)) {
        // https://github.com/DataDog/dd-trace-py/blob/864abb6c99e1cb0449904260bac93e8232261f2a/ddtrace/contrib/botocore/patch.py#L368
        List records =
            request
                .getValueForField("Records", List.class)
                .orElse(Collections.singletonList(request)); // For PutRecord use request

        for (Object ignored : records) {
          AgentTracer.get()
              .getDataStreamsMonitoring()
              .setProduceCheckpoint("kinesis", kinesisStreamArn.get());
        }
      } else if (snsTopicName.isPresent()
          && "sns".equalsIgnoreCase(awsServiceName)
          && SNS_PUBLISH_OPERATION_NAMES.contains(awsOperationName)) {
        List entries =
            request
                .getValueForField("PublishBatchRequestEntries", List.class)
                .orElse(Collections.singletonList(request));

        for (Object ignored : entries) {
          AgentTracer.get()
              .getDataStreamsMonitoring()
              .setProduceCheckpoint("sns", snsTopicName.get());
        }
      }
    }

    // Set peer.service based on Config for serverless functions
    if (Config.get().isAwsServerless()) {
      URI uri = httpRequest.getUri();
      String hostname = uri.getHost();
      if (uri.getPort() != -1) {
        hostname = hostname + ":" + uri.getPort();
      }

      span.setTag(Tags.PEER_SERVICE, hostname);
      span.setTag(DDTags.PEER_SERVICE_SOURCE, "peer.service");
    }

    return context;
  }

  private static AgentSpan onOperation(
      final AgentSpan span, final String awsServiceName, final String awsOperationName) {
    String awsRequestName = awsServiceName + "." + awsOperationName;
    span.setResourceName(awsRequestName, RESOURCE_NAME_PRIORITY);

    switch (awsRequestName) {
      case "Sqs.SendMessage":
      case "Sqs.SendMessageBatch":
      case "Sqs.ReceiveMessage":
      case "Sqs.DeleteMessage":
      case "Sqs.DeleteMessageBatch":
        if (SQS_SERVICE_NAME != null) {
          span.setServiceName(SQS_SERVICE_NAME, COMPONENT_NAME);
        }
        break;
      case "Sns.PublishBatch":
      case "Sns.Publish":
        if (SNS_SERVICE_NAME != null) {
          span.setServiceName(SNS_SERVICE_NAME, COMPONENT_NAME);
        }
        break;
      default:
        if (GENERIC_SERVICE_NAME != null) {
          span.setServiceName(GENERIC_SERVICE_NAME, COMPONENT_NAME);
        }
        break;
    }
    span.setTag(InstrumentationTags.AWS_AGENT, COMPONENT_NAME);
    span.setTag(InstrumentationTags.AWS_SERVICE, awsServiceName);
    span.setTag(InstrumentationTags.TOP_LEVEL_AWS_SERVICE, awsServiceName);
    span.setTag(InstrumentationTags.AWS_OPERATION, awsOperationName);

    return span;
  }

  private static void setPeerService(
      @Nonnull final AgentSpan span, @Nonnull final String precursor, @Nonnull final String value) {
    if (SpanNaming.instance().namingSchema().peerService().supports()) {
      span.setTag(Tags.PEER_SERVICE, value);
      span.setTag(DDTags.PEER_SERVICE_SOURCE, precursor);
    }
  }

  private static void setBucketName(AgentSpan span, String name) {
    span.setTag(InstrumentationTags.AWS_BUCKET_NAME, name);
    span.setTag(InstrumentationTags.BUCKET_NAME, name);
    setPeerService(span, InstrumentationTags.AWS_BUCKET_NAME, name);
  }

  private static Optional<String> getRequestKey(SdkRequest request) {
    Optional<String> key = Optional.empty();
    try {
      key = request.getValueForField("Key", String.class);
    } catch (ClassCastException ignored) {
      // Key is not always a string, like for dynamodb GetItemRequest
    }

    return key;
  }

  private static void setObjectKey(AgentSpan span, String key) {
    span.setTag(InstrumentationTags.AWS_OBJECT_KEY, key);
  }

  private static void setQueueName(AgentSpan span, String name) {
    span.setTag(InstrumentationTags.AWS_QUEUE_NAME, name);
    span.setTag(InstrumentationTags.QUEUE_NAME, name);
    setPeerService(span, InstrumentationTags.AWS_QUEUE_NAME, name);
  }

  private static void setTopicName(AgentSpan span, String name) {
    span.setTag(InstrumentationTags.AWS_TOPIC_NAME, name);
    span.setTag(InstrumentationTags.TOPIC_NAME, name);
    setPeerService(span, InstrumentationTags.AWS_TOPIC_NAME, name);
  }

  private static void setStreamName(AgentSpan span, String name) {
    span.setTag(InstrumentationTags.AWS_STREAM_NAME, name);
    span.setTag(InstrumentationTags.STREAM_NAME, name);
    setPeerService(span, InstrumentationTags.AWS_STREAM_NAME, name);
  }

  private static void setTableName(AgentSpan span, String name) {
    span.setTag(InstrumentationTags.AWS_TABLE_NAME, name);
    span.setTag(InstrumentationTags.TABLE_NAME, name);
    setPeerService(span, InstrumentationTags.AWS_TABLE_NAME, name);
  }

  public Context onSdkResponse(
      final Context context,
      final SdkResponse response,
      final SdkHttpResponse httpResponse,
      final ExecutionAttributes attributes) {

    final AgentSpan span = fromContext(context);
    Config config = Config.get();
    String serviceName = attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME);
    if (config.isCloudResponsePayloadTaggingEnabled()
        && config.isCloudPayloadTaggingEnabledFor(serviceName)) {
      awsPojoToTags(span, ConfigDefaults.DEFAULT_TRACE_CLOUD_PAYLOAD_RESPONSE_TAG, response);
    }

    if (response instanceof AwsResponse) {
      span.setTag(
          InstrumentationTags.AWS_REQUEST_ID,
          ((AwsResponse) response).responseMetadata().requestId());

      final String awsServiceName = attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME);
      final String awsOperationName = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
      if (traceConfig().isDataStreamsEnabled()
          && "kinesis".equalsIgnoreCase(awsServiceName)
          && "GetRecords".equals(awsOperationName)) {
        // https://github.com/DataDog/dd-trace-py/blob/864abb6c99e1cb0449904260bac93e8232261f2a/ddtrace/contrib/botocore/patch.py#L350
        String streamArn = attributes.getAttribute(KINESIS_STREAM_ARN_ATTRIBUTE);
        if (null != streamArn) {
          response
              .getValueForField("Records", List.class)
              .ifPresent(
                  recordsRaw -> {
                    //noinspection unchecked
                    List<SdkPojo> records = (List<SdkPojo>) recordsRaw;
                    if (!records.isEmpty()) {
                      DataStreamsTags tags = create("kinesis", INBOUND, streamArn);
                      if (null == kinesisApproximateArrivalTimestampField) {
                        Optional<SdkField<?>> maybeField =
                            records.get(0).sdkFields().stream()
                                .filter(f -> f.locationName().equals("ApproximateArrivalTimestamp"))
                                .findFirst();
                        if (maybeField.isPresent()) {
                          //noinspection unchecked
                          kinesisApproximateArrivalTimestampField =
                              (SdkField<Instant>) maybeField.get();
                        } else {
                          // shouldn't be possible
                          return;
                        }
                      }
                      for (SdkPojo record : records) {
                        Instant arrivalTime =
                            kinesisApproximateArrivalTimestampField.getValueOrDefault(record);
                        AgentDataStreamsMonitoring dataStreamsMonitoring =
                            AgentTracer.get().getDataStreamsMonitoring();
                        PathwayContext pathwayContext = dataStreamsMonitoring.newPathwayContext();
                        pathwayContext.setCheckpoint(
                            create(tags, arrivalTime.toEpochMilli(), 0),
                            dataStreamsMonitoring::add);
                        if (!span.context().getPathwayContext().isStarted()) {
                          span.context().mergePathwayContext(pathwayContext);
                        }
                      }
                    }
                  });
        }
      }

      if ("s3".equalsIgnoreCase(awsServiceName) && traceConfig().isDataStreamsEnabled()) {
        long responseSize = getResponseContentLength(httpResponse);
        span.setTag(Tags.HTTP_RESPONSE_CONTENT_LENGTH, responseSize);

        String key = getSpanTagAsString(span, InstrumentationTags.AWS_OBJECT_KEY);
        String bucket = getSpanTagAsString(span, InstrumentationTags.AWS_BUCKET_NAME);
        String awsOperation = getSpanTagAsString(span, InstrumentationTags.AWS_OPERATION);

        if (key != null && bucket != null && awsOperation != null) {
          if ("GetObject".equalsIgnoreCase(awsOperation)) {
            DataStreamsTags tags = createWithDataset("s3", INBOUND, bucket, key, bucket);
            AgentTracer.get()
                .getDataStreamsMonitoring()
                .setCheckpoint(span, create(tags, 0, responseSize));
          }

          if ("PutObject".equalsIgnoreCase(awsOperation)) {
            Object requestSize = span.getTag(Tags.HTTP_REQUEST_CONTENT_LENGTH);
            long payloadSize = 0;
            if (requestSize != null) {
              payloadSize = (long) requestSize;
            }

            DataStreamsTags tags = createWithDataset("s3", OUTBOUND, bucket, key, bucket);
            AgentTracer.get()
                .getDataStreamsMonitoring()
                .setCheckpoint(span, create(tags, 0, payloadSize));
          }
        }
      }
    }
    return span;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"aws-sdk"};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected String method(final SdkHttpRequest request) {
    return request.method().name();
  }

  @Override
  protected URI url(final SdkHttpRequest request) {
    return request.getUri();
  }

  @Override
  protected int status(final SdkHttpResponse response) {
    return response.statusCode();
  }

  @ParametersAreNonnullByDefault
  @Override
  public void set(SdkHttpRequest.Builder carrier, String key, String value) {
    carrier.putHeader(key, value);
  }

  @Override
  protected String getRequestHeader(SdkHttpRequest request, String headerName) {
    return request.firstMatchingHeader(headerName).orElse(null);
  }

  @Override
  protected String getResponseHeader(SdkHttpResponse response, String headerName) {
    return response.firstMatchingHeader(headerName).orElse(null);
  }

  private void awsPojoToTags(AgentSpan span, String tagsPrefix, Object pojo) {
    Collection<PayloadTagsData.PathAndValue> payloadTagsData = new ArrayList<>();
    List<Object> path = new ArrayList<>();
    collectPayloadTagsData(payloadTagsData, path, pojo);
    span.setTag(
        tagsPrefix,
        new PayloadTagsData(payloadTagsData.toArray(new PayloadTagsData.PathAndValue[0])));
  }

  private void collectPayloadTagsData(
      Collection<PayloadTagsData.PathAndValue> payloadTagsData, List<Object> path, Object object) {
    if (object instanceof SdkPojo) {
      SdkPojo pojo = (SdkPojo) object;
      for (SdkField<?> field : pojo.sdkFields()) {
        Object val = field.getValueOrDefault(pojo);
        path.add(field.locationName());
        collectPayloadTagsData(payloadTagsData, path, val);
        path.remove(path.size() - 1);
      }
    } else if (object instanceof Collection) {
      int index = 0;
      for (Object value : (Collection<?>) object) {
        path.add(index);
        collectPayloadTagsData(payloadTagsData, path, value);
        path.remove(path.size() - 1);
        index++;
      }
    } else if (object instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) object;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        path.add(entry.getKey().toString());
        collectPayloadTagsData(payloadTagsData, path, entry.getValue());
        path.remove(path.size() - 1);
      }
    } else if (object instanceof SdkBytes) {
      SdkBytes bytes = (SdkBytes) object;
      payloadTagsData.add(new PayloadTagsData.PathAndValue(path.toArray(), bytes.asInputStream()));
    } else if (object != null) {
      payloadTagsData.add(new PayloadTagsData.PathAndValue(path.toArray(), object));
    }
  }
}
