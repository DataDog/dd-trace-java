package datadog.trace.lambda;

import static datadog.trace.lambda.LambdaEventData.decode;
import static datadog.trace.lambda.LambdaEventData.get;
import static datadog.trace.lambda.LambdaEventData.headers;
import static datadog.trace.lambda.LambdaEventData.json;
import static datadog.trace.lambda.LambdaEventData.last;
import static datadog.trace.lambda.LambdaEventData.micros;
import static datadog.trace.lambda.LambdaEventData.part;
import static datadog.trace.lambda.LambdaEventData.service;
import static datadog.trace.lambda.LambdaEventData.string;
import static datadog.trace.lambda.LambdaEventData.tag;
import static datadog.trace.lambda.LambdaEventData.timestamp;

import java.util.Map;

/** Bottlecap's S3, DynamoDB and Kinesis event inference. */
final class LambdaRecordEvents {
  private LambdaRecordEvents() {}

  static LambdaTrigger s3(Map<String, Object> record, Map<String, String> mapping) {
    String bucket = string(record, "s3", "bucket", "name");
    String arn = string(record, "s3", "bucket", "arn");
    if (bucket.isEmpty() || arn.isEmpty()) {
      return null;
    }
    LambdaTrigger trigger =
        new LambdaTrigger("s3", arn, true)
            .span(
                "aws.s3",
                service(mapping, bucket, "lambda_s3", bucket),
                bucket,
                timestamp(string(record, "eventTime")));
    trigger.mapping(mapping, bucket, "lambda_s3");
    trigger.meta.put("event_name", string(record, "eventName"));
    trigger.meta.put("bucketname", bucket);
    trigger.meta.put("bucket_arn", arn);
    trigger.meta.put("object_key", string(record, "s3", "object", "key"));
    tag(trigger.meta, "object_size", get(record, "s3", "object", "size"));
    tag(trigger.meta, "object_etag", get(record, "s3", "object", "eTag"));
    return trigger;
  }

  static LambdaTrigger dynamodb(Map<String, Object> record, Map<String, String> mapping) {
    String arn = string(record, "eventSourceARN");
    String table = part(arn, "/", 1);
    if (table.isEmpty()) {
      return null;
    }
    LambdaTrigger trigger =
        new LambdaTrigger("dynamodb", arn, true)
            .span(
                "aws.dynamodb",
                service(mapping, table, "lambda_dynamodb", table),
                string(record, "eventName") + " " + table,
                micros(get(record, "dynamodb", "ApproximateCreationDateTime"), 1_000_000));
    trigger.mapping(mapping, table, "lambda_dynamodb");
    recordTags(trigger, record, arn);
    trigger.meta.put("table_name", table);
    tag(trigger.meta, "size_bytes", get(record, "dynamodb", "SizeBytes"));
    trigger.meta.put("stream_view_type", string(record, "dynamodb", "StreamViewType"));
    return trigger;
  }

  static LambdaTrigger kinesis(Map<String, Object> record, Map<String, String> mapping) {
    String arn = string(record, "eventSourceARN");
    if (arn.isEmpty()) {
      return null;
    }
    String stream = last(arn, "/");
    LambdaTrigger trigger =
        new LambdaTrigger("kinesis", arn, true)
            .span(
                "aws.kinesis",
                service(mapping, stream, "lambda_kinesis", stream),
                stream,
                micros(get(record, "kinesis", "approximateArrivalTimestamp"), 1_000_000));
    trigger.mapping(mapping, stream, "lambda_kinesis");
    recordTags(trigger, record, arn);
    trigger.meta.put("stream_name", stream);
    trigger.meta.put("shard_id", part(string(record, "eventID"), ":", 0));
    trigger.meta.put("partition_key", string(record, "kinesis", "partitionKey"));
    trigger.carrier = headers(get(json(decode(string(record, "kinesis", "data"))), "_datadog"));
    return trigger;
  }

  private static void recordTags(LambdaTrigger trigger, Map<String, Object> record, String arn) {
    trigger.meta.put("event_id", string(record, "eventID"));
    trigger.meta.put("event_name", string(record, "eventName"));
    trigger.meta.put("event_version", string(record, "eventVersion"));
    trigger.meta.put("event_source_arn", arn);
  }
}
