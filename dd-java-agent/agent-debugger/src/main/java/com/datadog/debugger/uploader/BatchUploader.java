package com.datadog.debugger.uploader;

import com.datadog.debugger.util.DebuggerMetrics;
import datadog.common.container.ContainerInfo;
import datadog.trace.api.Config;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles batching logic of upload requests sent to the intake */
public class BatchUploader {
  private static final Logger log = LoggerFactory.getLogger(BatchUploader.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;
  private static final MediaType APPLICATION_JSON = MediaType.parse("application/json");
  private static final String HEADER_DD_API_KEY = "DD-API-KEY";
  private static final String HEADER_DD_CONTAINER_ID = "Datadog-Container-ID";
  private final String containerId;

  static final int MAX_RUNNING_REQUESTS = 10;
  static final int MAX_ENQUEUED_REQUESTS = 20;
  static final int TERMINATION_TIMEOUT = 5;

  private final ExecutorService okHttpExecutorService;
  private final OkHttpClient client;
  private final HttpUrl urlBase;
  private final Callback responseCallback;
  private final String apiKey;
  private final DebuggerMetrics debuggerMetrics;
  private final boolean instrumentTheWorld;

  private final Phaser inflightRequests = new Phaser(1);

  public BatchUploader(Config config) {
    this(config, new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES));
  }

  BatchUploader(Config config, RatelimitedLogger ratelimitedLogger) {
    this(config, ratelimitedLogger, ContainerInfo.get().containerId);
  }

  // Visible for testing
  BatchUploader(Config config, RatelimitedLogger ratelimitedLogger, String containerId) {
    instrumentTheWorld = config.isDebuggerInstrumentTheWorld();
    String url = config.getFinalDebuggerSnapshotUrl();
    if (url == null || url.length() == 0) {
      throw new IllegalArgumentException("Snapshot url is empty");
    }
    urlBase = HttpUrl.parse(url);
    log.debug("Started SnapshotUploader with target url {}", urlBase);
    apiKey = config.getApiKey();
    responseCallback = new ResponseCallback(ratelimitedLogger, inflightRequests);
    // This is the same thing OkHttp Dispatcher is doing except thread naming and daemonization
    okHttpExecutorService =
        new ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new UploaderThreadFactory("dd-debugger-upload-http-dispatcher"));
    this.containerId = containerId;
    // Reusing connections causes non daemon threads to be created which causes agent to prevent app
    // from exiting. See https://github.com/square/okhttp/issues/4029 for some details.
    ConnectionPool connectionPool = new ConnectionPool(MAX_RUNNING_REQUESTS, 1, TimeUnit.SECONDS);
    // Use same timeout everywhere for simplicity
    Duration requestTimeout = Duration.ofSeconds(config.getDebuggerUploadTimeout());
    OkHttpClient.Builder clientBuilder =
        new OkHttpClient.Builder()
            .connectTimeout(requestTimeout)
            .writeTimeout(requestTimeout)
            .readTimeout(requestTimeout)
            .callTimeout(requestTimeout)
            .dispatcher(new Dispatcher(okHttpExecutorService))
            .connectionPool(connectionPool);

    if ("http".equals(urlBase.scheme())) {
      // force clear text when using http to avoid failures for JVMs without TLS
      // see: https://github.com/DataDog/dd-trace-java/pull/1582
      clientBuilder.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
    }
    client = clientBuilder.build();
    client.dispatcher().setMaxRequests(MAX_RUNNING_REQUESTS);
    // We are mainly talking to the same(ish) host so we need to raise this limit
    client.dispatcher().setMaxRequestsPerHost(MAX_RUNNING_REQUESTS);
    debuggerMetrics = DebuggerMetrics.getInstance(config);
  }

  public void upload(byte[] batch) {
    upload(batch, "");
  }

  public void upload(byte[] batch, String tags) {
    if (instrumentTheWorld) {
      // no upload in Instrument-The-World mode
      return;
    }
    try {
      if (canEnqueueMoreRequests()) {
        makeUploadRequest(batch, tags);
        debuggerMetrics.count("batch.uploaded", 1);
      } else {
        debuggerMetrics.count("request.queue.full", 1);
        log.warn("Cannot upload batch data: too many enqueued requests!");
      }
    } catch (final IllegalStateException | IOException e) {
      debuggerMetrics.count("batch.upload.error", 1);
      log.warn("Problem uploading batch!", e);
    }
  }

  /**
   * Note that this method is only visible for testing and should not be used from outside this
   * class.
   */
  OkHttpClient getClient() {
    return client;
  }

  private void makeUploadRequest(byte[] json, String tags) throws IOException {
    // use RequestBody.create(MediaType, byte[]) to avoid changing Content-Type to
    // "Content-Type: application/json; charset=UTF-8" which is not recognized
    int contentLength = json.length;
    RequestBody body = RequestBody.create(APPLICATION_JSON, json);
    debuggerMetrics.histogram("batch.uploader.request.size", contentLength);
    if (log.isDebugEnabled()) {
      log.debug("Uploading batch data size={} bytes", contentLength);
    }
    HttpUrl.Builder builder = urlBase.newBuilder();
    if (!tags.isEmpty()) {
      builder.addQueryParameter("ddtags", tags);
    }
    Request.Builder requestBuilder = new Request.Builder().url(builder.build()).post(body);
    if (apiKey != null) {
      if (apiKey.isEmpty()) {
        log.debug("API key is empty");
      }
      if (apiKey.length() != 32) {
        log.debug(
            "API key length is incorrect (truncated?) expected=32 actual={} API key={}...",
            apiKey.length(),
            apiKey.substring(0, Math.min(apiKey.length(), 6)));
      }
      requestBuilder.addHeader(HEADER_DD_API_KEY, apiKey);
    } else {
      log.debug("API key is null");
    }
    if (containerId != null) {
      requestBuilder.addHeader(HEADER_DD_CONTAINER_ID, containerId);
    }
    Request request = requestBuilder.build();
    log.debug("Sending request: {} CT: {}", request, request.body().contentType());
    client.newCall(request).enqueue(responseCallback);
    inflightRequests.register();
  }

  public void shutdown() {
    try {
      inflightRequests.awaitAdvanceInterruptibly(inflightRequests.arrive(), 10, TimeUnit.SECONDS);
    } catch (TimeoutException | InterruptedException ignored) {
      log.warn("Not all upload requests have been handled");
    }
    okHttpExecutorService.shutdownNow();
    try {
      okHttpExecutorService.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      // Note: this should only happen in main thread right before exiting, so eating up interrupted
      // state should be fine.
      log.warn("Wait for executor shutdown interrupted");
    }
    client.connectionPool().evictAll();
  }

  private boolean canEnqueueMoreRequests() {
    return client.dispatcher().queuedCallsCount() < MAX_ENQUEUED_REQUESTS;
  }

  // FIXME: we should unify all thread factories in common library
  private static class UploaderThreadFactory implements ThreadFactory {
    private final String name;

    public UploaderThreadFactory(final String name) {
      this.name = name;
    }

    @Override
    public Thread newThread(final Runnable r) {
      final Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    }
  }

  private static final class ResponseCallback implements Callback {

    private final RatelimitedLogger ratelimitedLogger;
    private final Phaser inflightRequests;

    public ResponseCallback(final RatelimitedLogger ratelimitedLogger, Phaser inflightRequests) {
      this.ratelimitedLogger = ratelimitedLogger;
      this.inflightRequests = inflightRequests;
    }

    @Override
    public void onFailure(final Call call, final IOException e) {
      inflightRequests.arriveAndDeregister();
      ratelimitedLogger.warn("Failed to upload batch to {}", call.request().url(), e);
    }

    @Override
    public void onResponse(final Call call, final Response response) {
      try {
        inflightRequests.arriveAndDeregister();
        if (response.isSuccessful()) {
          log.debug("Upload done");
        } else {
          ResponseBody body = response.body();
          // Retrieve body content for detailed error messages
          if (body != null && MediaType.get("application/json").equals(body.contentType())) {
            try {
              log.warn(
                  "Failed to upload batch: unexpected response code {} {} {}",
                  response.message(),
                  response.code(),
                  body.string());
            } catch (IOException ex) {
              log.warn("error while getting error message body", ex);
            }
          } else {
            log.warn(
                "Failed to upload batch: unexpected response code {} {}",
                response.message(),
                response.code());
          }
        }
      } finally {
        response.close();
      }
    }
  }
}
