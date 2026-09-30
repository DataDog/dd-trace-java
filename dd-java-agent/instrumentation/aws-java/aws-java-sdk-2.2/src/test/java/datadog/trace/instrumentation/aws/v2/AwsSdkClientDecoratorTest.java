package datadog.trace.instrumentation.aws.v2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Optional;
import org.tabletest.junit.TableTest;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.awscore.AwsExecutionAttribute;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.regions.Region;

public class AwsSdkClientDecoratorTest {

  @TableTest({
    "Scenario   | Service         | Enrich",
    "DynamoDB   | DynamoDb        | true  ",
    "Timestream | TimestreamWrite | false ",
    "Athena     | Athena          | false "
  })
  void onlyDynamoDbTablesReceiveOwnership(String service, boolean enrich) {
    AgentSpan span = mock(AgentSpan.class, RETURNS_SELF);
    Context context = mock(Context.class);
    when(context.get(any())).thenReturn(span);
    SdkRequest request = mock(SdkRequest.class);
    when(request.getValueForField("TableName", String.class)).thenReturn(Optional.of("orders"));
    ExecutionAttributes attributes = new ExecutionAttributes();
    attributes.putAttribute(SdkExecutionAttribute.SERVICE_NAME, service);
    attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, "WriteRecords");
    attributes.putAttribute(AwsExecutionAttribute.AWS_REGION, Region.US_EAST_1);
    attributes.putAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS, new AccountCredentials());

    AwsSdkClientDecorator.DECORATE.onSdkRequest(
        context, request, mock(SdkHttpRequest.class), attributes);

    verify(span).setTag("aws.table.name", "orders");
    verify(span).setTag("tablename", "orders");
    if (enrich) {
      verify(span).setTag("aws_account", "123456789012");
    } else {
      verify(span, never()).setTag(eq("aws_account"), anyString());
    }
    verify(span, never()).setTag(eq("aws.table.arn"), anyString());
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
    Context context = mock(Context.class);
    when(context.get(any())).thenReturn(span);
    SdkRequest request = mock(SdkRequest.class);
    when(request.getValueForField("TableName", String.class)).thenReturn(Optional.of(tableName));
    ExecutionAttributes attributes = new ExecutionAttributes();
    attributes.putAttribute(SdkExecutionAttribute.SERVICE_NAME, "DynamoDb");
    attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, "GetItem");

    AwsSdkClientDecorator.DECORATE.onSdkRequest(
        context, request, mock(SdkHttpRequest.class), attributes);

    verify(span).setTag("aws.table.name", tableName);
    verify(span).setTag("tablename", tableName);
    if (tableArn) {
      verify(span).setTag("aws.table.arn", tableName);
    } else {
      verify(span, never()).setTag(eq("aws.table.arn"), anyString());
    }
  }

  /** Exposes the newer credentials API while compiling against the SDK 2.2.0 baseline. */
  public static class AccountCredentials implements AwsCredentials {
    public Optional<String> accountId() {
      return Optional.of("123456789012");
    }

    @Override
    public String accessKeyId() {
      return "test";
    }

    @Override
    public String secretAccessKey() {
      return "test";
    }
  }
}
