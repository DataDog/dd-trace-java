package datadog.trace.instrumentation.aws.v0;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.services.kinesis.model.AddTagsToStreamRequest;
import com.amazonaws.services.kinesis.model.CreateStreamRequest;
import com.amazonaws.services.kinesis.model.DecreaseStreamRetentionPeriodRequest;
import com.amazonaws.services.kinesis.model.DeleteStreamRequest;
import com.amazonaws.services.kinesis.model.DescribeStreamRequest;
import com.amazonaws.services.kinesis.model.DisableEnhancedMonitoringRequest;
import com.amazonaws.services.kinesis.model.EnableEnhancedMonitoringRequest;
import com.amazonaws.services.kinesis.model.GetShardIteratorRequest;
import com.amazonaws.services.kinesis.model.IncreaseStreamRetentionPeriodRequest;
import com.amazonaws.services.kinesis.model.ListTagsForStreamRequest;
import com.amazonaws.services.kinesis.model.MergeShardsRequest;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.kinesis.model.PutRecordsRequest;
import com.amazonaws.services.kinesis.model.RemoveTagsFromStreamRequest;
import com.amazonaws.services.kinesis.model.SplitShardRequest;
import com.amazonaws.services.kinesis.model.UpdateShardCountRequest;
import datadog.trace.instrumentation.api.AgentSpan;

public final class KinesisClientDecorator extends AwsSdkClientDecorator {
  public static final KinesisClientDecorator DECORATE = new KinesisClientDecorator();
  public static final String AWS_STREAM_NAME_TAG = "aws.stream.name";

  private KinesisClientDecorator() {}

  @Override
  public void onOriginalRequest(
      final AgentSpan span, final AmazonWebServiceRequest originalRequest) {
    if (originalRequest instanceof AddTagsToStreamRequest) {
      span.setTag(AWS_STREAM_NAME_TAG, ((AddTagsToStreamRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof CreateStreamRequest) {
      span.setTag(AWS_STREAM_NAME_TAG, ((CreateStreamRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof DecreaseStreamRetentionPeriodRequest) {
      span.setTag(
          AWS_STREAM_NAME_TAG,
          ((DecreaseStreamRetentionPeriodRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof DescribeStreamRequest) {
      span.setTag(AWS_STREAM_NAME_TAG, ((DescribeStreamRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof DeleteStreamRequest) {
      span.setTag(AWS_STREAM_NAME_TAG, ((DeleteStreamRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof DisableEnhancedMonitoringRequest) {
      span.setTag(
          AWS_STREAM_NAME_TAG,
          ((DisableEnhancedMonitoringRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof EnableEnhancedMonitoringRequest) {
      span.setTag(
          AWS_STREAM_NAME_TAG, ((EnableEnhancedMonitoringRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof GetShardIteratorRequest) {
      span.setTag(AWS_STREAM_NAME_TAG, ((GetShardIteratorRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof IncreaseStreamRetentionPeriodRequest) {
      span.setTag(
          AWS_STREAM_NAME_TAG,
          ((IncreaseStreamRetentionPeriodRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof ListTagsForStreamRequest) {
      span.setTag(
          AWS_STREAM_NAME_TAG, ((ListTagsForStreamRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof MergeShardsRequest) {
      span.setTag(AWS_STREAM_NAME_TAG, ((MergeShardsRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof PutRecordRequest) {
      span.setTag(AWS_STREAM_NAME_TAG, ((PutRecordRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof PutRecordsRequest) {
      span.setTag(AWS_STREAM_NAME_TAG, ((PutRecordsRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof RemoveTagsFromStreamRequest) {
      span.setTag(
          AWS_STREAM_NAME_TAG, ((RemoveTagsFromStreamRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof SplitShardRequest) {
      span.setTag(AWS_STREAM_NAME_TAG, ((SplitShardRequest) originalRequest).getStreamName());
    } else if (originalRequest instanceof UpdateShardCountRequest) {
      span.setTag(AWS_STREAM_NAME_TAG, ((UpdateShardCountRequest) originalRequest).getStreamName());
    }
  }
}
