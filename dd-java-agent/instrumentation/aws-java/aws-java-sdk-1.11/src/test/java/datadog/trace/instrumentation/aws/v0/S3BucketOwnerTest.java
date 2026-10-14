package datadog.trace.instrumentation.aws.v0;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.DefaultRequest;
import com.amazonaws.Response;
import com.amazonaws.http.HttpMethodName;
import com.amazonaws.http.HttpResponse;
import datadog.context.Context;
import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.net.URI;
import org.tabletest.junit.TableTest;

public class S3BucketOwnerTest {

  @TableTest({
    "Scenario          | Owner        | Status | Expected owner",
    "Success           | 123456789012 | 200    | 123456789012  ",
    "Owner rejected    | 999999999999 | 403    |               ",
    "Server failure    | 123456789012 | 500    |               ",
    "Transport failure | 123456789012 | 0      |               ",
    "Malformed owner   | invalid      | 200    |               ",
    "Missing owner     |              | 200    |               "
  })
  void tagsOwnerOnlyAfterSuccess(String owner, int status, String expectedOwner) {
    AgentSpan span = mock(AgentSpan.class, RETURNS_SELF);
    when(span.traceConfig()).thenReturn(mock(TraceConfig.class));
    Context context = mock(Context.class);
    when(context.get(any())).thenReturn(span);
    DefaultRequest<AmazonWebServiceRequest> request =
        new DefaultRequest<>(new BucketRequest(owner), "Amazon S3");
    request.setEndpoint(URI.create("http://localhost"));
    request.setHttpMethod(HttpMethodName.GET);
    request.addHandlerContext(TracingRequestHandler.CONTEXT_CONTEXT_KEY, context);

    AwsSdkClientDecorator.DECORATE.onRequest(span, request);
    verify(span, never()).setTag(eq("aws_account"), anyString());

    HttpResponse httpResponse = mock(HttpResponse.class);
    when(httpResponse.getStatusCode()).thenReturn(status);
    Response<Object> response = new Response<>(new Object(), httpResponse);
    TracingRequestHandler handler = new TracingRequestHandler(null, null);
    if (status == 200) {
      handler.afterResponse(request, response);
    } else {
      handler.afterError(request, status == 0 ? null : response, new IOException("request failed"));
    }

    if (expectedOwner == null) {
      verify(span, never()).setTag(eq("aws_account"), anyString());
    } else {
      verify(span).setTag("aws_account", expectedOwner);
    }
    verify(span).finish();
  }

  /** Exposes the newer request getter while compiling against the SDK 1.11 baseline. */
  public static class BucketRequest extends AmazonWebServiceRequest {
    private final String owner;

    public BucketRequest(String owner) {
      this.owner = owner;
    }

    public String getExpectedBucketOwner() {
      return owner;
    }

    public String getBucketName() {
      return "somebucket";
    }
  }
}
