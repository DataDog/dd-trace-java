import com.openai.core.RequestOptions;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import org.jetbrains.annotations.NotNull;
import java.util.concurrent.CompletableFuture;

public class TestOpenAiHttpClient implements HttpClient {

  private final HttpClient delegate;

  public TestOpenAiHttpClient(HttpClient delegate) {
    this.delegate = delegate;
  }

  @Override
  public void close() {
    delegate.close();
  }

  @NotNull
  @Override
  public HttpResponse execute(@NotNull HttpRequest httpRequest, @NotNull RequestOptions requestOptions) {
    // System.err.println(">>> " + httpRequest.pathSegments());
    // httpRequest.body().writeTo();
    HttpResponse response = delegate.execute(httpRequest, requestOptions);

    //TODO dump request and response to a file to be replayed in OpenAiTest when run in mock mode
    return response;
  }

  @NotNull
  @Override
  public CompletableFuture<HttpResponse> executeAsync(@NotNull HttpRequest httpRequest, @NotNull RequestOptions requestOptions) {
    return delegate.executeAsync(httpRequest, requestOptions);
  }
}
