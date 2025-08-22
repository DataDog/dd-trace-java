package datadog.trace.instrumentation.aws;

import java.util.Arrays;
import java.util.List;

public final class ExpectedQueryParams {
  private ExpectedQueryParams() {}

  public static List<String> getExpectedQueryParams(String operation) {
    switch (operation) {
      case "Publish":
        return Arrays.asList("Action", "Version", "TopicArn", "Message");
      case "PublishBatch":
        return Arrays.asList(
            "Action",
            "Version",
            "TopicArn",
            "PublishBatchRequestEntries.member.1.Id",
            "PublishBatchRequestEntries.member.1.Message",
            "PublishBatchRequestEntries.member.2.Id",
            "PublishBatchRequestEntries.member.2.Message");
      case "AllocateAddress":
      case "DeleteOptionGroup":
        return Arrays.asList("Action", "Version");
      case "CreateQueue":
      case "GetQueueUrl":
        return Arrays.asList("Action", "Version", "QueueName");
      case "SendMessage":
        return Arrays.asList("Action", "Version", "QueueUrl", "MessageBody");
      case "DeleteMessage":
        return Arrays.asList("Action", "Version", "QueueUrl", "ReceiptHandle");
      case "ReceiveMessage":
        return Arrays.asList("Action", "Version", "QueueUrl", "AttributeName.1");
      case "SendMessageBatch":
        return Arrays.asList(
            "Action",
            "Version",
            "QueueUrl",
            "SendMessageBatchRequestEntry.1.Id",
            "SendMessageBatchRequestEntry.1.MessageBody",
            "SendMessageBatchRequestEntry.2.Id",
            "SendMessageBatchRequestEntry.2.MessageBody",
            "SendMessageBatchRequestEntry.3.Id",
            "SendMessageBatchRequestEntry.3.MessageBody",
            "SendMessageBatchRequestEntry.4.Id",
            "SendMessageBatchRequestEntry.4.MessageBody",
            "SendMessageBatchRequestEntry.5.Id",
            "SendMessageBatchRequestEntry.5.MessageBody");
      case "DeleteMessageBatch":
        return Arrays.asList(
            "Action",
            "Version",
            "QueueUrl",
            "DeleteMessageBatchRequestEntry.1.Id",
            "DeleteMessageBatchRequestEntry.1.ReceiptHandle");
      case "Mule":
        return Arrays.asList("name");
      default:
        return null;
    }
  }
}
