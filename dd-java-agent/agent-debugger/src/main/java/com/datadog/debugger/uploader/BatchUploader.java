package com.datadog.debugger.uploader;

import static datadog.trace.util.AgentThreadFactory.AgentThread.DEBUGGER_HTTP_DISPATCHER;

import com.datadog.debugger.util.DebuggerMetrics;
import datadog.common.container.ContainerInfo;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.relocate.api.RatelimitedLogger;
import datadog.trace.util.AgentThreadFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles batching logic of upload requests sent to the intake */
public class BatchUploader {
  public static class MultiPartContent {
    private final byte[] content;
    private final String partName;
    private final String fileName;

    public MultiPartContent(byte[] content, String partName, String fileName) {
      this.content = content;
      this.partName = partName;
      this.fileName = fileName;
    }

    public byte[] getContent() {
      return content;
    }

    public String getPartName() {
      return partName;
    }

    public String getFileName() {
      return fileName;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(BatchUploader.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;
  private static final MediaType APPLICATION_JSON = MediaType.parse("application/json");
  private static final String HEADER_DD_CONTAINER_ID = "Datadog-Container-ID";
  private static final String HEADER_DD_ENTITY_ID = "Datadog-Entity-ID";
  static final String HEADER_DD_API_KEY = "DD-API-KEY";
  static final int MAX_RUNNING_REQUESTS = 10;
  static final int MAX_ENQUEUED_REQUESTS = 20;
  static final int TERMINATION_TIMEOUT = 5;

  private final String containerId;
  private final String entityId;
  private final ExecutorService okHttpExecutorService;
  private final OkHttpClient client;
  private final HttpUrl urlBase;
  private final Callback responseCallback;
  private final String apiKey;
  private final DebuggerMetrics debuggerMetrics;
  private final boolean instrumentTheWorld;
  private final RatelimitedLogger ratelimitedLogger;

  private final Phaser inflightRequests = new Phaser(1);

  public BatchUploader(Config config, String endpoint) {
    this(config, endpoint, new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES));
  }

  BatchUploader(Config config, String endpoint, RatelimitedLogger ratelimitedLogger) {
    this(
        config,
        endpoint,
        ratelimitedLogger,
        ContainerInfo.get().containerId,
        ContainerInfo.getEntityId());
  }

  // Visible for testing
  BatchUploader(
      Config config,
      String endpoint,
      RatelimitedLogger ratelimitedLogger,
      String containerId,
      String entityId) {
    instrumentTheWorld = config.isDebuggerInstrumentTheWorld();
    if (endpoint == null || endpoint.length() == 0) {
      throw new IllegalArgumentException("Endpoint url is empty");
    }
    urlBase = HttpUrl.get(endpoint);
    log.debug("Started BatchUploader with target url {}", urlBase);
    apiKey = config.getApiKey();
    this.ratelimitedLogger = ratelimitedLogger;
    responseCallback = new ResponseCallback(ratelimitedLogger, inflightRequests);
    // This is the same thing OkHttp Dispatcher is doing except thread naming and daemonization
    okHttpExecutorService =
        new ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new AgentThreadFactory(DEBUGGER_HTTP_DISPATCHER));
    this.containerId = containerId;
    this.entityId = entityId;
    Duration requestTimeout = Duration.ofSeconds(config.getDebuggerUploadTimeout());
    client =
        OkHttpUtils.buildHttpClient(
            config,
            new Dispatcher(okHttpExecutorService),
            urlBase,
            true, /* retry */
            MAX_RUNNING_REQUESTS,
            null, /* proxyHost */
            null, /* proxyPort */
            null, /* proxyUsername */
            null, /* proxyPassword */
            requestTimeout.toMillis());
    debuggerMetrics = DebuggerMetrics.getInstance(config);
  }

  public void upload(byte[] batch) {
    upload(batch, "");
  }

  public void upload(byte[] batch, String tags) {
    doUpload(() -> makeUploadRequest(batch, tags));
  }

  public void uploadAsMultipart(String tags, MultiPartContent... parts) {
    doUpload(() -> makeMultipartUploadRequest(tags, parts));
  }

  private void makeMultipartUploadRequest(String tags, MultiPartContent[] parts) {
    MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
    int contentLength = 0;
    for (MultiPartContent part : parts) {
      contentLength += addPart(builder, part);
    }
    MultipartBody body = builder.build();
    buildAndSendRequest(body, contentLength, tags);
  }

  private int addPart(MultipartBody.Builder builder, MultiPartContent part) {
    RequestBody fileBody = RequestBody.create(APPLICATION_JSON, part.content);
    builder.addFormDataPart(part.partName, part.fileName, fileBody);
    return part.content.length;
  }

  private void doUpload(Runnable makeRequest) {
    if (instrumentTheWorld) {
      // no upload in Instrument-The-World mode
      return;
    }
    try {
      if (canEnqueueMoreRequests()) {
        makeRequest.run();
        debuggerMetrics.count("batch.uploaded", 1);
      } else {
        debuggerMetrics.count("request.queue.full", 1);
        ratelimitedLogger.warn("Cannot upload batch data: too many enqueued requests!");
      }
    } catch (Exception ex) {
      debuggerMetrics.count("batch.upload.error", 1);
      ratelimitedLogger.warn("Problem uploading batch!", ex);
    }
  }

  /**
   * Note that this method is only visible for testing and should not be used from outside this
   * class.
   */
  OkHttpClient getClient() {
    return client;
  }

  public HttpUrl getUrl() {
    return urlBase;
  }

  private void makeUploadRequest(byte[] json, String tags) {
    int contentLength = json.length;
    // use RequestBody.create(MediaType, byte[]) to avoid changing Content-Type to
    // "Content-Type: application/json; charset=UTF-8" which is not recognized
    RequestBody body = RequestBody.create(APPLICATION_JSON, json);
    buildAndSendRequest(body, contentLength, tags);
  }

  private void buildAndSendRequest(RequestBody body, int contentLength, String tags) {
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
    if (entityId != null) {
      requestBuilder.addHeader(HEADER_DD_ENTITY_ID, entityId);
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
              ratelimitedLogger.warn(
                  "Failed to upload batch: unexpected response code {} {} {}",
                  response.message(),
                  response.code(),
                  body.string());
            } catch (IOException ex) {
              ratelimitedLogger.warn("error while getting error message body", ex);
            }
          } else {
            ratelimitedLogger.warn(
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
