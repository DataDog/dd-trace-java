package com.datadog.featureflag;

import datadog.communication.BackendApi;
import datadog.communication.HttpResponseException;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends Feature Flag events through a local EVP proxy, with a safe direct intake fallback. */
final class AgentlessFeatureFlagBackendApi implements BackendApi {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(AgentlessFeatureFlagBackendApi.class);

  private final BackendApi proxyApi;
  private final Supplier<BackendApi> directApiSupplier;
  private final String eventType;
  private volatile BackendApi activeApi;
  private volatile boolean directApiCreationAttempted;

  AgentlessFeatureFlagBackendApi(
      final BackendApi proxyApi,
      final Supplier<BackendApi> directApiSupplier,
      final String eventType) {
    this.proxyApi = proxyApi;
    this.directApiSupplier = directApiSupplier;
    this.eventType = eventType;
    this.activeApi = proxyApi;
  }

  @Override
  public <T> T post(
      final String uri,
      final RequestBody requestBody,
      final IOThrowingFunction<InputStream, T> responseParser,
      @Nullable final OkHttpUtils.CustomListener requestListener,
      final boolean requestCompression)
      throws IOException {
    final BackendApi selectedApi = activeApi;
    try {
      return selectedApi.post(
          uri, requestBody, responseParser, requestListener, requestCompression);
    } catch (final IOException exception) {
      if (selectedApi != proxyApi) {
        throw exception;
      }

      if (isDefinitiveRejection(exception)) {
        final BackendApi directApi = getOrCreateDirectApi();
        if (directApi != null) {
          return directApi.post(
              uri, requestBody, responseParser, requestListener, requestCompression);
        }
      } else if (isAmbiguousTransportFailure(exception)) {
        // The local receiver may have accepted this batch, so only switch future batches.
        getOrCreateDirectApi();
      }
      throw exception;
    }
  }

  @Nullable
  private BackendApi getOrCreateDirectApi() {
    final BackendApi selectedApi = activeApi;
    if (selectedApi != proxyApi) {
      return selectedApi;
    }

    synchronized (this) {
      final BackendApi currentApi = activeApi;
      if (currentApi != proxyApi) {
        return currentApi;
      }
      if (directApiCreationAttempted) {
        return null;
      }

      final BackendApi directApi = directApiSupplier.get();
      if (directApi != null) {
        LOGGER.debug(
            "Switching Feature Flagging {} delivery from the local EVP proxy to direct intake",
            eventType);
        activeApi = directApi;
      }
      directApiCreationAttempted = true;
      return directApi;
    }
  }

  private static boolean isDefinitiveRejection(final IOException exception) {
    if (exception instanceof ConnectException) {
      return true;
    }
    if (exception instanceof HttpResponseException) {
      final int statusCode = ((HttpResponseException) exception).getStatusCode();
      return statusCode == 403 || statusCode == 404 || statusCode == 405;
    }
    return false;
  }

  private static boolean isAmbiguousTransportFailure(final IOException exception) {
    return !(exception instanceof HttpResponseException);
  }
}
