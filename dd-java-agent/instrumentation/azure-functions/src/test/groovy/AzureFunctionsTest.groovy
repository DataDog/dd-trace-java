import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

import com.microsoft.azure.functions.ExecutionContext
import com.microsoft.azure.functions.HttpMethod
import com.microsoft.azure.functions.HttpRequestMessage
import com.microsoft.azure.functions.HttpResponseMessage
import com.microsoft.azure.functions.HttpStatus
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.internal.Version

abstract class AzureFunctionsTest extends VersionedNamingTestBase {

  @Override
  String service() {
    null
  }

  def "test azure functions http trigger"() {
    given:
    HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage)
    HttpResponseMessage.Builder responseBuilder = mock(HttpResponseMessage.Builder)
    HttpResponseMessage response = mock(HttpResponseMessage)
    ExecutionContext context = mock(ExecutionContext)

    String functionName = "HttpTest"
    Map<String, String> headers = ["user-agent": Version.userAgent()]
    HttpMethod method = HttpMethod.GET
    String responseBody = "Hello Datadog test!"
    HttpStatus status = HttpStatus.OK
    int statusCode = 200
    URI uri = new URI("https://localhost:7071/api/HttpTest")

    and:
    when(request.getHeaders()).thenReturn(headers)
    when(request.getHttpMethod()).thenReturn(method)
    when(request.getUri()).thenReturn(uri)
    when(request.createResponseBuilder(status)).thenReturn(responseBuilder)

    when(responseBuilder.body(responseBody)).thenReturn(responseBuilder)
    when(responseBuilder.build()).thenReturn(response)

    when(response.getStatusCode()).thenReturn(statusCode)
    when(response.getBody()).thenReturn(responseBody)

    when(context.getFunctionName()).thenReturn(functionName)

    when:
    new Function().run(request, context)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName operation()
          spanType DDSpanTypes.SERVERLESS
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "azure-functions"
            "$Tags.SPAN_KIND" "$Tags.SPAN_KIND_SERVER"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_ROUTE" "/api/HttpTest"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_URL" "https://localhost:7071/api/HttpTest"
            "$Tags.HTTP_USER_AGENT" "${Version.userAgent()}"
            "aas.function.name" "HttpTest"
            "aas.function.trigger" "Http"
          }
        }
      }
    }
  }
}


class AzureFunctionsV0ForkedTest extends AzureFunctionsTest {
  @Override
  int version() {
    0
  }

  @Override
  String operation() {
    "dd-tracer-serverless-span"
  }
}

class AzureFunctionsV1Test extends AzureFunctionsTest {
  @Override
  int version() {
    1
  }

  @Override
  String operation() {
    "azure.functions.invoke"
  }
}
