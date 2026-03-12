package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.RPC_COMMAND_NAME;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.AmazonWebServiceResponse;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.http.HttpMethodName;
import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.ParametersAreNonnullByDefault;

public class AwsSdkClientDecorator extends HttpClientDecorator<Request, Response>
    implements CarrierSetter<Request<?>> {

  private static final String AWS = "aws";

  static final CharSequence COMPONENT_NAME = UTF8BytesString.create("java-aws-sdk");

  public static final boolean AWS_LEGACY_TRACING = Config.get().isAwsLegacyTracingEnabled();

  public static final boolean SQS_LEGACY_TRACING = Config.get().isSqsLegacyTracingEnabled();

  private static final String SQS_SERVICE_NAME =
      // this is probably wrong since it should use SpanNaming.instance()...
      // but at this point changing the naming will be a breaking change
      AWS_LEGACY_TRACING || SQS_LEGACY_TRACING ? "sqs" : Config.get().getServiceName();

  private static final String SNS_SERVICE_NAME =
      SpanNaming.instance().namingSchema().cloud().serviceForRequest(AWS, "sns");
  private static final String GENERIC_SERVICE_NAME =
      SpanNaming.instance().namingSchema().cloud().serviceForRequest(AWS, null);
  public static final AwsSdkClientDecorator DECORATE = new AwsSdkClientDecorator();

  private static final DDCache<String, String> serviceNameCache = DDCaches.newFixedSizeCache(128);
  private static final Pattern AWS_SERVICE_NAME_PATTERN = Pattern.compile("Amazon\\s?(\\w+)");

  private static final String PUT_RECORD_OPERATION_NAME = "PutRecordRequest";
  private static final String PUT_RECORDS_OPERATION_NAME = "PutRecordsRequest";
  private static final String PUBLISH_OPERATION_NAME = "PublishRequest";
  private static final String PUBLISH_BATCH_OPERATION_NAME = "PublishBatchRequest";

  private static String simplifyServiceName(String awsServiceName) {
    return serviceNameCache.computeIfAbsent(
        awsServiceName, AwsSdkClientDecorator::applyServiceNamePattern);
  }

  private static String applyServiceNamePattern(String awsServiceName) {
    if (awsServiceName != null) {
      Matcher matcher = AWS_SERVICE_NAME_PATTERN.matcher(awsServiceName);
      if (matcher.find()) {
        return matcher.group(1).toLowerCase(Locale.ROOT);
      }
    }
    return awsServiceName;
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final Request request) {
    // Call super first because we override the resource name below.
    super.onRequest(span, request);

    final String awsServiceName = request.getServiceName();
    final String awsSimplifiedServiceName = simplifyServiceName(awsServiceName);
    final AmazonWebServiceRequest originalRequest = request.getOriginalRequest();
    final Class<?> awsOperation = originalRequest.getClass();
    final GetterAccess access = GetterAccess.of(originalRequest);
    final String endpoint = request.getEndpoint().toString();

    span.setTag(InstrumentationTags.AWS_AGENT, COMPONENT_NAME);
    span.setTag(InstrumentationTags.AWS_SERVICE, awsServiceName);
    span.setTag(InstrumentationTags.TOP_LEVEL_AWS_SERVICE, awsSimplifiedServiceName);
    span.setTag(InstrumentationTags.AWS_OPERATION, awsOperation.getSimpleName());
    span.setTag(InstrumentationTags.AWS_ENDPOINT, endpoint);

    CharSequence awsRequestName = AwsNameCache.getQualifiedName(request);
    span.setResourceName(awsRequestName, RPC_COMMAND_NAME);

    if ("s3".equalsIgnoreCase(awsSimplifiedServiceName) && traceConfig().isDataStreamsEnabled()) {
      span.setTag(Tags.HTTP_REQUEST_CONTENT_LENGTH, getRequestContentLength(request));
    }

    switch (awsRequestName.toString()) {
      case "SQS.SendMessage":
      case "SQS.SendMessageBatch":
      case "SQS.ReceiveMessage":
      case "SQS.DeleteMessage":
      case "SQS.DeleteMessageBatch":
        span.setServiceName(SQS_SERVICE_NAME, component());
        break;
      case "SNS.Publish":
      case "SNS.PublishBatch":
        if (SNS_SERVICE_NAME != null) {
          span.setServiceName(SNS_SERVICE_NAME, component());
        }
        break;
      default:
        if (GENERIC_SERVICE_NAME != null) {
          span.setServiceName(GENERIC_SERVICE_NAME, component());
        }
        break;
    }

    String bestPrecursor = null;
    String bestPeerService = null;

    String key = access.getKey(originalRequest);
    if (null != key) {
      span.setTag(InstrumentationTags.AWS_OBJECT_KEY, key);
    }
    String bucketName = access.getBucketName(originalRequest);
    if (null != bucketName) {
      span.setTag(InstrumentationTags.AWS_BUCKET_NAME, bucketName);
      span.setTag(InstrumentationTags.BUCKET_NAME, bucketName);
      bestPrecursor = InstrumentationTags.AWS_BUCKET_NAME;
      bestPeerService = bucketName;
    }
    String queueUrl = access.getQueueUrl(originalRequest);
    if (null != queueUrl) {
      span.setTag(InstrumentationTags.AWS_QUEUE_URL, queueUrl);
      bestPrecursor = InstrumentationTags.AWS_QUEUE_URL;
      bestPeerService = queueUrl;
    }
    String queueName = access.getQueueName(originalRequest);
    if (null != queueName) {
      span.setTag(InstrumentationTags.AWS_QUEUE_NAME, queueName);
      span.setTag(InstrumentationTags.QUEUE_NAME, queueName);
      bestPrecursor = InstrumentationTags.AWS_QUEUE_NAME;
      bestPeerService = queueName;
    }
    String topicName = null;
    String topicArn = access.getTopicArn(originalRequest);
    if (null != topicArn) {
      topicName = topicArn.substring(topicArn.lastIndexOf(':') + 1);
      span.setTag(InstrumentationTags.AWS_TOPIC_NAME, topicName);
      span.setTag(InstrumentationTags.TOPIC_NAME, topicName);
      bestPrecursor = InstrumentationTags.AWS_TOPIC_NAME;
      bestPeerService = topicName;
    }
    String streamName = access.getStreamName(originalRequest);
    if (null != streamName) {
      span.setTag(InstrumentationTags.AWS_STREAM_NAME, streamName);
      span.setTag(InstrumentationTags.STREAM_NAME, streamName);
      bestPrecursor = InstrumentationTags.AWS_STREAM_NAME;
      bestPeerService = streamName;
    }
    String streamArn = access.getStreamARN(originalRequest);
    if (null != streamArn) {
      int streamNameStart = streamArn.indexOf(":stream/");
      if (streamNameStart >= 0) {
        streamName = streamArn.substring(streamNameStart + 8);
        span.setTag(InstrumentationTags.AWS_STREAM_NAME, streamName);
        span.setTag(InstrumentationTags.STREAM_NAME, streamName);
        bestPrecursor = InstrumentationTags.AWS_STREAM_NAME;
        bestPeerService = streamName;
      }
    }
    String tableName = access.getTableName(originalRequest);
    if (null != tableName) {
      span.setTag(InstrumentationTags.AWS_TABLE_NAME, tableName);
      span.setTag(InstrumentationTags.TABLE_NAME, tableName);
      bestPrecursor = InstrumentationTags.AWS_TABLE_NAME;
      bestPeerService = tableName;
    }

    // Set peer.service based on Config for serverless functions
    if (Config.get().isAwsServerless()) {
      URI uri = request.getEndpoint();
      String hostname = uri.getHost();
      if (uri.getPort() != -1) {
        hostname = hostname + ":" + uri.getPort();
      }
      span.setTag(Tags.PEER_SERVICE, hostname);
      span.setTag(DDTags.PEER_SERVICE_SOURCE, "peer.service");
    } else {
      if (bestPrecursor != null && SpanNaming.instance().namingSchema().peerService().supports()) {
        span.setTag(Tags.PEER_SERVICE, bestPeerService);
        span.setTag(DDTags.PEER_SERVICE_SOURCE, bestPrecursor);
      }
    }

    // DSM
    if (traceConfig().isDataStreamsEnabled()) {
      if (null != streamArn && "AmazonKinesis".equals(awsServiceName)) {
        switch (awsOperation.getSimpleName()) {
          case PUT_RECORD_OPERATION_NAME:
            try (AgentScope scope = AgentTracer.activateSpan(span)) {
              AgentTracer.get()
                  .getDataStreamsMonitoring()
                  .setProduceCheckpoint("kinesis", streamArn);
            }
            break;
          case PUT_RECORDS_OPERATION_NAME:
            try (AgentScope scope = AgentTracer.activateSpan(span)) {
              List records = access.getRecords(originalRequest);
              for (Object ignored : records) {
                AgentTracer.get()
                    .getDataStreamsMonitoring()
                    .setProduceCheckpoint("kinesis", streamArn);
              }
            }
            break;
          default:
            break;
        }
      } else if (null != topicName && "AmazonSNS".equals(awsServiceName)) {
        switch (awsOperation.getSimpleName()) {
          case PUBLISH_OPERATION_NAME:
            try (AgentScope scope = AgentTracer.activateSpan(span)) {
              AgentTracer.get().getDataStreamsMonitoring().setProduceCheckpoint("sns", topicName);
            }
            break;
          case PUBLISH_BATCH_OPERATION_NAME:
            try (AgentScope scope = AgentTracer.activateSpan(span)) {
              List entries = access.getEntries(originalRequest);
              for (Object ignored : entries) {
                AgentTracer.get().getDataStreamsMonitoring().setProduceCheckpoint("sns", topicName);
              }
            }
            break;
          default:
            break;
        }
      }
    }

    return span;
  }

  public AgentSpan onServiceResponse(
      final AgentSpan span, final String awsService, final Response response) {
    if ("s3".equalsIgnoreCase(simplifyServiceName(awsService))
        && traceConfig().isDataStreamsEnabled()) {
      long responseSize = getResponseContentLength(response);
      span.setTag(Tags.HTTP_RESPONSE_CONTENT_LENGTH, responseSize);

      String key = getSpanTagAsString(span, InstrumentationTags.AWS_OBJECT_KEY);
      String bucket = getSpanTagAsString(span, InstrumentationTags.AWS_BUCKET_NAME);
      String awsOperation = getSpanTagAsString(span, InstrumentationTags.AWS_OPERATION);

      if (key != null && bucket != null && awsOperation != null) {
        // GetObjectMetadataRequest may return the object if it's not "HEAD"
        if (HttpMethodName.GET.name().equals(span.getTag(Tags.HTTP_METHOD))
            && ("GetObjectMetadataRequest".equalsIgnoreCase(awsOperation)
                || "GetObjectRequest".equalsIgnoreCase(awsOperation))) {
          DataStreamsTags tags =
              DataStreamsTags.createWithDataset("s3", INBOUND, bucket, key, bucket);
          AgentTracer.get()
              .getDataStreamsMonitoring()
              .setCheckpoint(span, create(tags, 0, responseSize));
        }

        if ("PutObjectRequest".equalsIgnoreCase(awsOperation)
            || "UploadPartRequest".equalsIgnoreCase(awsOperation)) {
          Object requestSize = span.getTag(Tags.HTTP_REQUEST_CONTENT_LENGTH);
          long payloadSize = 0;
          if (requestSize != null) {
            payloadSize = (long) requestSize;
          }
          DataStreamsTags tags =
              DataStreamsTags.createWithDataset("s3", OUTBOUND, bucket, key, bucket);
          AgentTracer.get()
              .getDataStreamsMonitoring()
              .setCheckpoint(span, create(tags, 0, payloadSize));
        }
      }
    }

    return span;
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final Response response) {
    if (response.getAwsResponse() instanceof AmazonWebServiceResponse) {
      final AmazonWebServiceResponse awsResp = (AmazonWebServiceResponse) response.getAwsResponse();
      span.setTag(InstrumentationTags.AWS_REQUEST_ID, awsResp.getRequestId());
    }

    return super.onResponse(span, response);
  }

  @Override
  protected boolean shouldSetResourceName() {
    return false;
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
  protected String method(final Request request) {
    return request.getHttpMethod().name();
  }

  @Override
  protected URI url(final Request request) {
    return request.getEndpoint();
  }

  @Override
  protected int status(final Response response) {
    return response.getHttpResponse().getStatusCode();
  }

  @ParametersAreNonnullByDefault
  @Override
  public void set(Request<?> carrier, String key, String value) {
    carrier.addHeader(key, value);
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    Object header = request.getHeaders().get(headerName);
    if (null != header) {
      return header.toString();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return response.getHttpResponse().getHeaders().get(headerName);
  }
}
