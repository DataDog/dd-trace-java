package datadog.trace.instrumentation.aws.v0;

import com.amazonaws.handlers.HandlerContextKey;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;

public class RequestMeta {
  // Note: aws1.x sdk doesn't have any truly async clients so we can store scope in request context
  // safely.
  public static final HandlerContextKey<AgentScope> SCOPE_CONTEXT_KEY =
      new HandlerContextKey<>("DatadogScope");

  private String bucketName;
  private String queueUrl;
  private String queueName;
  private String streamName;
  private String tableName;

  public String getBucketName() {
    return bucketName;
  }

  public String getQueueUrl() {
    return queueUrl;
  }

  public String getQueueName() {
    return queueName;
  }

  public String getStreamName() {
    return streamName;
  }

  public String getTableName() {
    return tableName;
  }

  public void setBucketName(String bucketName) {
    this.bucketName = bucketName;
  }

  public void setQueueUrl(String queueUrl) {
    this.queueUrl = queueUrl;
  }

  public void setQueueName(String queueName) {
    this.queueName = queueName;
  }

  public void setStreamName(String streamName) {
    this.streamName = streamName;
  }

  public void setTableName(String tableName) {
    this.tableName = tableName;
  }
}
