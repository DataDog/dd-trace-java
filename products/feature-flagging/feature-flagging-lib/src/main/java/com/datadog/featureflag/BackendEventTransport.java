package com.datadog.featureflag;

import datadog.communication.BackendApi;
import java.util.function.Supplier;
import okhttp3.MediaType;
import okhttp3.RequestBody;

/** Preserves the existing BackendApi routing and retry semantics. */
final class BackendEventTransport {
  private static final MediaType JSON = MediaType.parse("application/json");

  private BackendEventTransport() {}

  static Supplier<EventTransport> adapt(Supplier<BackendApi> supplier) {
    return () -> {
      BackendApi api = supplier.get();
      return api == null
          ? null
          : (route, json) ->
              api.post(route, RequestBody.create(JSON, json), stream -> null, null, false);
    };
  }
}
