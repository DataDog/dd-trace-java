package com.datadog.featureflag;

import datadog.communication.BackendApi;
import datadog.communication.HttpResponseException;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import javax.annotation.Nullable;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends Feature Flag events through a local EVP proxy, with a safe direct intake fallback. */
final class AgentlessFeatureFlagBackendApi implements BackendApi {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(AgentlessFeatureFlagBackendApi.class);

  private final BackendApi localApi;
  private final BackendApi directApi;
  private final String eventType;
  private volatile BackendApi activeApi;

  AgentlessFeatureFlagBackendApi(
      final BackendApi localApi, final BackendApi directApi, final String eventType) {
    this.localApi = localApi;
    this.directApi = directApi;
    this.eventType = eventType;
    this.activeApi = localApi;
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
      if (selectedApi != localApi || !isDefinitiveRejection(exception)) {
        throw exception;
      }

      if (activeApi == localApi) {
        LOGGER.debug(
            "Switching Feature Flagging {} delivery from the local EVP proxy to direct intake",
            eventType);
        activeApi = directApi;
      }
      return directApi.post(uri, requestBody, responseParser, requestListener, requestCompression);
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
}
