package datadog.telemetry;

import java.util.function.Supplier;
import okhttp3.HttpUrl;

class RequestBuilderSupplier implements Supplier<RequestBuilder> {
  private final HttpUrl httpUrl;
  private RequestBuilder requestBuilder;

  RequestBuilderSupplier(HttpUrl httpUrl) {
    this.httpUrl = httpUrl;
  }

  @Override
  public RequestBuilder get() {
    if (requestBuilder == null) {
      requestBuilder = new RequestBuilder(httpUrl);
    }
    return requestBuilder;
  }
}
