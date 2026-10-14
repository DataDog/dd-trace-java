package datadog.trace.instrumentation.aws.v0;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.DefaultRequest;
import com.amazonaws.http.HttpMethodName;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.URI;
import org.tabletest.junit.TableTest;

class AwsSdkClientDecoratorTest {

  @TableTest({
    "Scenario          | Service               | Enrich",
    "DynamoDB          | AmazonDynamoDB        | true  ",
    "DynamoDB v2       | AmazonDynamoDBv2      | true  ",
    "Other table model | AmazonTimestreamWrite | false ",
    "Athena            | AmazonAthena          | false "
  })
  void onlyDynamoDbTablesReceiveOwnership(String service, boolean enrich) {
    AgentSpan span = mock(AgentSpan.class, RETURNS_SELF);
    when(span.traceConfig()).thenReturn(mock(TraceConfig.class));
    String tableArn = "arn:aws:dynamodb:us-east-1:123456789012:table/orders";
    // Reuse a TableName-bearing model to verify that the service controls enrichment.
    DefaultRequest<GetItemRequest> request =
        new DefaultRequest<>(new GetItemRequest().withTableName(tableArn), service);
    request.setEndpoint(URI.create("http://localhost"));
    request.setHttpMethod(HttpMethodName.POST);

    AwsSdkClientDecorator.DECORATE.onRequest(span, request);

    verify(span).setTag("aws.table.name", tableArn);
    verify(span).setTag("tablename", tableArn);
    if (enrich) {
      verify(span).setTag("aws_account", "123456789012");
      verify(span).setTag("aws.table.arn", tableArn);
    } else {
      verify(span, never()).setTag(eq("aws_account"), anyString());
      verify(span, never()).setTag(eq("aws.table.arn"), anyString());
    }
  }

  @TableTest({
    "Scenario   | Table Name                                            | Table Arn",
    "Table      | arn:aws:dynamodb:us-east-1:123456789012:table/orders  | true     ",
    "Non-table  | arn:aws:dynamodb:us-east-1:123456789012:backup/orders | false    ",
    "Empty name | arn:aws:dynamodb:us-east-1:123456789012:table/        | false    ",
    "SNS ARN    | arn:aws:sns:us-east-1:123456789012:table/orders       | false    ",
    "S3 ARN     | arn:aws:s3:::my-bucket                                | false    "
  })
  void preservesTableNamesAndOnlyTagsTableArns(String tableName, boolean tableArn) {
    AgentSpan span = mock(AgentSpan.class, RETURNS_SELF);
    when(span.traceConfig()).thenReturn(mock(TraceConfig.class));
    DefaultRequest<GetItemRequest> request =
        new DefaultRequest<>(new GetItemRequest().withTableName(tableName), "AmazonDynamoDBv2");
    request.setEndpoint(URI.create("http://localhost"));
    request.setHttpMethod(HttpMethodName.POST);

    AwsSdkClientDecorator.DECORATE.onRequest(span, request);

    verify(span).setTag("aws.table.name", tableName);
    verify(span).setTag("tablename", tableName);
    if (tableArn) {
      verify(span).setTag("aws.table.arn", tableName);
    } else {
      verify(span, never()).setTag(eq("aws.table.arn"), anyString());
    }
  }
}
