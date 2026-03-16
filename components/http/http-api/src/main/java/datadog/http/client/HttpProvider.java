package datadog.http.client;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;

/** Factory class providing the various HTTP client class implementations. */
public abstract class HttpProvider {
  public abstract HttpClient.Builder newClientBuilder();

  public abstract HttpRequest.Builder newRequestBuilder();

  public abstract HttpUrl.Builder newUrlBuilder();

  public abstract HttpUrl httpUrlParse(String url);

  public abstract HttpUrl httpUrlFrom(URI uri);

  public abstract HttpRequestBody requestBodyOfString(String content);

  public abstract HttpRequestBody requestBodyOfBytes(byte[] bytes);

  public abstract HttpRequestBody requestBodyOfByteBuffers(List<ByteBuffer> buffers);

  public abstract HttpRequestBody requestBodyGzip(HttpRequestBody body) throws IOException;

  public abstract HttpRequestBody.MultipartBuilder requestBodyMultipart();
}
