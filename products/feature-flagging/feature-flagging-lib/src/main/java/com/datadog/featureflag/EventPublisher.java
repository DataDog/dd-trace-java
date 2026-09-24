package com.datadog.featureflag;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.function.Supplier;

class EventPublisher<T> {

  private final Supplier<EventTransport> backendApiSupplier;
  private final JsonAdapter<T> jsonAdapter;
  private EventTransport evp;

  EventPublisher(final Supplier<EventTransport> backendApiSupplier, final Class<T> requestType) {
    this.backendApiSupplier = backendApiSupplier;
    this.jsonAdapter = new Moshi.Builder().build().adapter(requestType);
  }

  boolean start() {
    if (evp == null) {
      evp = backendApiSupplier.get();
    }
    return evp != null;
  }

  void post(final String route, final T request) throws IOException {
    post(route, serialize(request));
  }

  byte[] serialize(final T request) {
    return utf8Bytes(jsonAdapter.toJson(request));
  }

  void post(final String route, final byte[] json) throws IOException {
    if (!start()) {
      throw new IllegalStateException("EVP Proxy not available");
    }
    evp.post(route, json);
  }

  static byte[] utf8Bytes(final String json) {
    try {
      return json.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError("UTF-8 must be available", e);
    }
  }
}
