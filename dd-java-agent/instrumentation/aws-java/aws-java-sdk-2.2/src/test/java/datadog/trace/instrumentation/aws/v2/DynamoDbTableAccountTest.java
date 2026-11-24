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

/** Which account, if any, a DynamoDB or other TableName request is tagged with. */
public class DynamoDbTableAccountTest {

  @TableTest({
    "Scenario   | Service         | Enrich",
    "DynamoDB   | DynamoDb        | true  ",
    "Timestream | TimestreamWrite | false ",
    "Athena     | Athena          | false "
  })
  void onlyDynamoDbBareNamesUseTheCredentialsAccount(String service, boolean enrich) {
    AgentSpan span = tagBareRequest(service, "orders");

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
    "Scenario      | Table Name                                            | Table Arn | Account     ",
    "Table         | arn:aws:dynamodb:us-east-1:210987654321:table/orders  | true      | 210987654321",
    "Non-table     | arn:aws:dynamodb:us-east-1:210987654321:backup/orders | false     |             ",
    "Empty name    | arn:aws:dynamodb:us-east-1:210987654321:table/        | false     |             ",
    "Short account | arn:aws:dynamodb:us-east-1:2109:table/orders          | false     |             ",
    "SNS ARN       | arn:aws:sns:us-east-1:210987654321:table/orders       | false     |             ",
    "S3 ARN        | arn:aws:s3:::my-bucket                                | false     |             "
  })
  void arnValuesNeverFallBackToTheCredentialsAccount(
      String tableName, boolean tableArn, String account) {
    // The credentials carry 123456789012, which must not appear for any ARN form
    AgentSpan span = tagBareRequest("DynamoDb", tableName);

    verify(span).setTag("aws.table.name", tableName);
    verify(span).setTag("tablename", tableName);
    if (tableArn) {
      verify(span).setTag("aws.table.arn", tableName);
    } else {
      verify(span, never()).setTag(eq("aws.table.arn"), anyString());
    }
    if (account != null) {
      verify(span).setTag("aws_account", account);
    } else {
      verify(span, never()).setTag(eq("aws_account"), anyString());
    }
  }

  private static AgentSpan tagBareRequest(String service, String tableName) {
    AgentSpan span = mock(AgentSpan.class, RETURNS_SELF);
    Context context = mock(Context.class);
    when(context.get(any())).thenReturn(span);
    SdkRequest request = mock(SdkRequest.class);
    when(request.getValueForField("TableName", String.class)).thenReturn(Optional.of(tableName));
    ExecutionAttributes attributes = new ExecutionAttributes();
    attributes.putAttribute(SdkExecutionAttribute.SERVICE_NAME, service);
    attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, "GetItem");
    attributes.putAttribute(AwsExecutionAttribute.AWS_REGION, Region.US_EAST_1);
    attributes.putAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS, new AccountCredentials());

    AwsSdkClientDecorator.DECORATE.onSdkRequest(
        context, request, mock(SdkHttpRequest.class), attributes);
    return span;
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
