import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import java.util.Optional;

public class Function {
  @FunctionName("HttpTest")
  public HttpResponseMessage run(
      @HttpTrigger(
              name = "req",
              methods = {HttpMethod.GET},
              authLevel = AuthorizationLevel.ANONYMOUS)
          HttpRequestMessage<Optional<String>> request,
      final ExecutionContext context) {
    return request.createResponseBuilder(HttpStatus.OK).body("Hello Datadog test!").build();
  }
}
