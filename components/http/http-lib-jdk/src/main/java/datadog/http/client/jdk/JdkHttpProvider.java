package datadog.http.client.jdk;

import datadog.http.client.HttpClient;
import datadog.http.client.HttpProvider;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpUrl;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;

/** {@link HttpProvider} for JDK HttpClient based implemenations. */
@SuppressWarnings("unused")
public final class JdkHttpProvider extends HttpProvider {
  @Override
  public HttpClient.Builder newClientBuilder() {
    return new JdkHttpClient.Builder();
  }

  @Override
  public HttpRequest.Builder newRequestBuilder() {
    return new JdkHttpRequest.Builder();
  }

  @Override
  public HttpUrl.Builder newUrlBuilder() {
    return new JdkHttpUrl.Builder();
  }

  @Override
  public HttpUrl httpUrlParse(String url) {
    return JdkHttpUrl.parse(url);
  }

  @Override
  public HttpUrl httpUrlFrom(URI uri) {
    return JdkHttpUrl.from(uri);
  }

  @Override
  public HttpRequestBody requestBodyOfString(String content) {
    return JdkHttpRequestBody.ofString(content);
  }

  @Override
  public HttpRequestBody requestBodyOfBytes(byte[] bytes) {
    return JdkHttpRequestBody.ofBytes(bytes);
  }

  @Override
  public HttpRequestBody requestBodyOfByteBuffers(List<ByteBuffer> buffers) {
    return JdkHttpRequestBody.ofByteBuffers(buffers);
  }

  @Override
  public HttpRequestBody requestBodyGzip(HttpRequestBody body) throws IOException {
    return JdkHttpRequestBody.ofGzip(body);
  }

  @Override
  public HttpRequestBody.MultipartBuilder requestBodyMultipart() {
    return new JdkHttpRequestBody.MultipartBuilder();
  }
}
