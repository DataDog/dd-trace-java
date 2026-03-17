package datadog.http.client.okhttp;

import datadog.http.client.HttpClient;
import datadog.http.client.HttpProvider;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpUrl;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;

/** {@link HttpProvider} for OkHttp 3 based implemenations. */
@SuppressWarnings("unused")
public final class OkHttpProvider extends HttpProvider {
  @Override
  public HttpClient.Builder newClientBuilder() {
    return new OkHttpClient.Builder();
  }

  @Override
  public HttpRequest.Builder newRequestBuilder() {
    return new OkHttpRequest.Builder();
  }

  @Override
  public HttpUrl.Builder newUrlBuilder() {
    return new OkHttpUrl.Builder();
  }

  @Override
  public HttpUrl httpUrlParse(String url) {
    return OkHttpUrl.parse(url);
  }

  @Override
  public HttpUrl httpUrlFrom(URI uri) {
    return OkHttpUrl.from(uri);
  }

  @Override
  public HttpRequestBody requestBodyOfString(String content) {
    return OkHttpRequestBody.ofString(content);
  }

  @Override
  public HttpRequestBody requestBodyOfBytes(byte[] bytes) {
    return OkHttpRequestBody.ofBytes(bytes);
  }

  @Override
  public HttpRequestBody requestBodyOfByteBuffers(List<ByteBuffer> buffers) {
    return OkHttpRequestBody.ofByteBuffers(buffers);
  }

  @Override
  public HttpRequestBody requestBodyGzip(HttpRequestBody body) {
    return OkHttpRequestBody.ofGzip(body);
  }

  @Override
  public HttpRequestBody.MultipartBuilder requestBodyMultipart() {
    return new OkHttpRequestBody.MultipartBuilder();
  }
}
