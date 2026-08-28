package com.datadog.featureflag;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.trace.api.intake.Intake;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.function.Supplier;
import okhttp3.MediaType;
import okhttp3.RequestBody;

final class FeatureFlagEvpPublisher<T> {

  private static final MediaType JSON = MediaType.parse("application/json");

  private final Supplier<BackendApi> backendApiSupplier;
  private final JsonAdapter<T> jsonAdapter;
  private BackendApi evp;

  FeatureFlagEvpPublisher(final BackendApiFactory backendApiFactory, final Class<T> requestType) {
    this(backendApiFactory, requestType, true);
  }

  FeatureFlagEvpPublisher(
      final BackendApiFactory backendApiFactory,
      final Class<T> requestType,
      final boolean responseCompression) {
    this(
        () -> backendApiFactory.createBackendApi(Intake.EVENT_PLATFORM, responseCompression),
        requestType);
  }

  FeatureFlagEvpPublisher(
      final Supplier<BackendApi> backendApiSupplier, final Class<T> requestType) {
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
    final RequestBody requestBody = RequestBody.create(JSON, json);
    evp.post(route, requestBody, stream -> null, null, false);
  }

  static byte[] utf8Bytes(final String json) {
    try {
      return json.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError("UTF-8 must be available", e);
    }
  }
}
