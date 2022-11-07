package datadog.telemetry;

import com.squareup.moshi.JsonAdapter;
import datadog.telemetry.api.Telemetry;
import datadog.trace.api.function.Supplier;
import okhttp3.HttpUrl;

class RequestBuilderSupplier implements Supplier<RequestBuilder> {
  final JsonAdapter<Telemetry> jsonAdapter;
  private final HttpUrl httpUrl;
  private RequestBuilder requestBuilder;

  RequestBuilderSupplier(final JsonAdapter<Telemetry> jsonAdapter, final HttpUrl httpUrl) {
    this.jsonAdapter = jsonAdapter;
    this.httpUrl = httpUrl;
  }

  @Override
  public RequestBuilder get() {
    if (requestBuilder == null) {
      requestBuilder = new RequestBuilder(jsonAdapter, httpUrl);
    }
    return requestBuilder;
  }
}
