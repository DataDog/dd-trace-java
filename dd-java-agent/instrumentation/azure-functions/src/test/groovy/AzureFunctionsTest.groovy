import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

import com.microsoft.azure.functions.ExecutionContext
import com.microsoft.azure.functions.HttpMethod
import com.microsoft.azure.functions.HttpRequestMessage
import com.microsoft.azure.functions.HttpResponseMessage
import com.microsoft.azure.functions.HttpStatus
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes

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

    and:
    when(request.getHeaders()).thenReturn(Collections.emptyMap())
    when(request.getHttpMethod()).thenReturn(HttpMethod.GET)
    when(request.getUri()).thenReturn(new URI("https://localhost:7071/api/HttpTest"))
    when(request.createResponseBuilder(HttpStatus.OK)).thenReturn(responseBuilder)

    when(responseBuilder.body("Hello Datadog test!")).thenReturn(responseBuilder)
    when(responseBuilder.build()).thenReturn(response)

    when(response.getStatusCode()).thenReturn(200)
    when(response.getBody()).thenReturn("Hello Datadog test!")

    when(context.getFunctionName()).thenReturn("HttpTest")

    when:
    new Function().run(request, context)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          spanType DDSpanTypes.SERVERLESS
          errored false
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
