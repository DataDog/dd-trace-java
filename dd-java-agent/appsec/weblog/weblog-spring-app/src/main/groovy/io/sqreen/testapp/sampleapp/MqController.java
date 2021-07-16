package io.sqreen.testapp.sampleapp;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;
import java.util.Date;
import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mq")
public class MqController {

  private static final String DEFAULT_USER = "user";
  private static final String DEFAULT_PASS = "dummy";

  @RequestMapping(value = "/sqs")
  public void awsSqsConnector() {
    String endpoint = "http://localhost:4576";

    BasicAWSCredentials awsCreds = new BasicAWSCredentials(DEFAULT_USER, DEFAULT_PASS);
    EndpointConfiguration endpointConfiguration = new EndpointConfiguration(endpoint, "eu-west-3");
    AmazonSQS sqs =
        AmazonSQSClientBuilder.standard()
            .withCredentials(new AWSStaticCredentialsProvider(awsCreds))
            .withEndpointConfiguration(endpointConfiguration)
            .build();
    CreateQueueResult result = sqs.createQueue("MyQueue" + new Date().getTime());
    String queueUrl = result.getQueueUrl();
    String messageBody = "test message";

    SendMessageRequest request =
        new SendMessageRequest().withQueueUrl(queueUrl).withMessageBody(messageBody);
    sqs.sendMessage(request);

    sqs.sendMessageBatch(
        new SendMessageBatchRequest()
            .withQueueUrl(queueUrl)
            .withEntries(
                new SendMessageBatchRequestEntry("msg_1", "hello"),
                new SendMessageBatchRequestEntry("msg_2", "world")));

    List<Message> messages = sqs.receiveMessage(queueUrl).getMessages();
  }

  @RequestMapping(value = "/sqs-async")
  public void awsSqsAsyncConnector() {
    String endpoint = "http://localhost:4568";

    BasicAWSCredentials awsCreds = new BasicAWSCredentials(DEFAULT_USER, DEFAULT_PASS);
    EndpointConfiguration endpointConfiguration = new EndpointConfiguration(endpoint, "eu-west-3");
    AmazonSQSAsync sqs =
        AmazonSQSAsyncClientBuilder.standard()
            .withCredentials(new AWSStaticCredentialsProvider(awsCreds))
            .withEndpointConfiguration(endpointConfiguration)
            .build();

    CreateQueueResult result = sqs.createQueue("MyQueue" + new Date().getTime());
    String queueUrl = result.getQueueUrl();
    String messageBody = "test message";

    SendMessageRequest request =
        new SendMessageRequest().withQueueUrl(queueUrl).withMessageBody(messageBody);
    sqs.sendMessageAsync(request);

    sqs.sendMessageBatchAsync(
        new SendMessageBatchRequest()
            .withQueueUrl(queueUrl)
            .withEntries(
                new SendMessageBatchRequestEntry("msg_1", "hello"),
                new SendMessageBatchRequestEntry("msg_2", "world")));

    List<Message> messages = sqs.receiveMessage(queueUrl).getMessages();
  }
}
