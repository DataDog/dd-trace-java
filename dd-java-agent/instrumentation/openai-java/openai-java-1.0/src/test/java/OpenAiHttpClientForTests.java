import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

// Wraps httpClient calls to dump request/responses records to be used with the mocked backend
public class OpenAiHttpClientForTests implements HttpClient {
  private final Path recordsDir;
  private final HttpClient delegate;

  // Intercepts and dumps a request/response to a record file
  public OpenAiHttpClientForTests(HttpClient delegate, Path recordsDir) {
    this.recordsDir = recordsDir;
    this.delegate = delegate;
  }

  @NotNull
  @Override
  public HttpResponse execute(@NotNull HttpRequest request, @NotNull RequestOptions requestOptions) {
    HttpResponse response = delegate.execute(request, requestOptions);
    return wrapIfNeeded(request, response);
  }

  @NotNull
  @Override
  public CompletableFuture<HttpResponse> executeAsync(@NotNull HttpRequest request, @NotNull RequestOptions requestOptions) {
    return delegate.executeAsync(request, requestOptions)
        .thenApply(response -> wrapIfNeeded(request, response));
  }

  @Override
  public void close() {
    delegate.close();
  }

  private HttpResponse wrapIfNeeded(HttpRequest request, HttpResponse response) {
    if (RequestResponseRecord.exists(recordsDir, request)) {
      // will NOT record if the record exists
      return response;
    }
    return new ResponseRequestInterceptor(request, response, recordsDir);
  }

  private static class ResponseRequestInterceptor implements HttpResponse {
    private final HttpRequest request;
    private final HttpResponse response;
    private final Path recordsDir;
    private final ByteArrayOutputStream responseBody;

    private ResponseRequestInterceptor(HttpRequest request, HttpResponse response, Path recordsDir) {
      this.request = request;
      this.response = response;
      this.recordsDir = recordsDir;
      responseBody = new ByteArrayOutputStream();
    }

    @Override
    public int statusCode() {
      return response.statusCode();
    }

    @NotNull
    @Override
    public Headers headers() {
      return response.headers();
    }

    @NotNull
    @Override
    public InputStream body() {
      InputStream body = response.body();
      return new InputStream() {
        @Override
        public int read() throws IOException {
          int b = body.read();
          // capture body while it's consumed
          responseBody.write(b);
          return b;
        }
      };
    }

    @Override
    public void close() {
      try {
        Path targetDir = recordsDir;
        for (String segment : request.pathSegments()) {
          targetDir = targetDir.resolve(segment);
        }
        Files.createDirectories(targetDir);
        RequestResponseRecord.dump(targetDir, request, response, responseBody.toByteArray());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      response.close();
    }
  }
}
