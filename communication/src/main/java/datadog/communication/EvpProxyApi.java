package datadog.communication;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** API that uses DD Agent as a proxy to post request to backend. */
public class EvpProxyApi implements BackendApi {

  private static final Logger log = LoggerFactory.getLogger(EvpProxyApi.class);

  private static final String API_VERSION = "v2";
  private static final String X_DATADOG_EVP_SUBDOMAIN_HEADER = "X-Datadog-EVP-Subdomain";
  private static final String X_DATADOG_TRACE_ID_HEADER = "x-datadog-trace-id";
  private static final String X_DATADOG_PARENT_ID_HEADER = "x-datadog-parent-id";
  private static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";
  private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
  private static final String GZIP_ENCODING = "gzip";

  private final String traceId;
  private final HttpRetryPolicy.Factory retryPolicyFactory;
  private final HttpUrl evpProxyUrl;
  private final String subdomain;
  private final OkHttpClient httpClient;
  private final boolean responseCompression;

  public EvpProxyApi(
      String traceId,
      HttpUrl evpProxyUrl,
      String subdomain,
      HttpRetryPolicy.Factory retryPolicyFactory,
      OkHttpClient httpClient,
      boolean responseCompression) {
    this.traceId = traceId;
    this.evpProxyUrl = evpProxyUrl.resolve(String.format("api/%s/", API_VERSION));
    this.subdomain = subdomain;
    this.retryPolicyFactory = retryPolicyFactory;
    this.httpClient = httpClient;
    this.responseCompression = responseCompression;
  }

  @Override
  public <T> T post(
      String uri,
      RequestBody requestBody,
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable OkHttpUtils.CustomListener requestListener,
      boolean requestCompression)
      throws IOException {
    final HttpUrl url = evpProxyUrl.resolve(uri);

    Request.Builder requestBuilder =
        new Request.Builder()
            .url(url)
            .addHeader(X_DATADOG_EVP_SUBDOMAIN_HEADER, subdomain)
            .addHeader(X_DATADOG_TRACE_ID_HEADER, traceId)
            .addHeader(X_DATADOG_PARENT_ID_HEADER, traceId);

    if (requestListener != null) {
      requestBuilder.tag(OkHttpUtils.CustomListener.class, requestListener);
    }

    if (requestCompression) {
      requestBuilder.addHeader(CONTENT_ENCODING_HEADER, GZIP_ENCODING);
    }

    if (responseCompression) {
      requestBuilder.addHeader(ACCEPT_ENCODING_HEADER, GZIP_ENCODING);
    }

    final Request request = requestBuilder.post(requestBody).build();

    try (okhttp3.Response response =
        OkHttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request)) {
      if (response.isSuccessful()) {
        log.debug("Request to {} returned successful response: {}", uri, response.code());

        InputStream responseBodyStream = response.body().byteStream();

        String contentEncoding = response.header(CONTENT_ENCODING_HEADER);
        if (GZIP_ENCODING.equalsIgnoreCase(contentEncoding)) {
          log.debug("Response content encoding is {}, unzipping response body", contentEncoding);
          responseBodyStream = new GZIPInputStream(responseBodyStream);
        }

        return responseParser.apply(responseBodyStream);
      } else {
        throw new IOException(
            "Request to "
                + uri
                + " returned error response "
                + response.code()
                + ": "
                + response.message()
                + "; "
                + (response.body() != null ? response.body().string() : ""));
      }
    }
  }
}
