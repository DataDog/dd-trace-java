package com.datadog.debugger.sink;

import static com.datadog.debugger.uploader.BatchUploader.APPLICATION_GZIP;
import static com.datadog.debugger.uploader.BatchUploader.APPLICATION_JSON;

import com.datadog.debugger.symbol.Scope;
import com.datadog.debugger.symbol.ScopeType;
import com.datadog.debugger.symbol.ServiceVersion;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Config;
import datadog.trace.util.RandomUtils;
import datadog.trace.util.Strings;
import datadog.trace.util.TagsHelper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okio.BufferedSink;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolSink {

  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolSink.class);
  static final int CAPACITY = 1024;
  public static final BatchUploader.RetryPolicy RETRY_POLICY = new BatchUploader.RetryPolicy(10);
  private static final JsonAdapter<ServiceVersion> SERVICE_VERSION_ADAPTER =
      new ServiceVersion.ServiceVersionAdapter();
  private static final JsonAdapter<Scope> SCOPE_ADAPTER =
      MoshiHelper.createMoshiSymbol().adapter(Scope.class);
  // The upload event message JSON. The "final" field is hard-coded to false:
  // the Java tracer continuously uploads new code as classes get loaded, so
  // there is no defined end-of-upload point.
  private static final String EVENT_FORMAT =
      "{%n"
          + "\"ddsource\": \"dd_debugger\",%n"
          + "\"service\": \"%s\",%n"
          + "\"version\": \"%s\",%n"
          + "\"language\": \"java\",%n"
          + "\"runtimeId\": \"%s\",%n"
          + "\"type\": \"symdb\",%n"
          + "\"uploadId\": \"%s\",%n"
          + "\"batchNum\": %d,%n"
          + "\"final\": false,%n"
          + "\"attachmentSize\": %d%n"
          + "}";
  static final int MAX_SYMDB_UPLOAD_SIZE = 50 * 1024 * 1024;
  // min & max cumulative size (in chars) of pre-serialized scopes kept in the queue before flushing
  static final long MIN_QUEUED_SCOPES_SIZE = 4 * 1024 * 1024;
  static final long MAX_QUEUED_SCOPES_SIZE = 128 * 1024 * 1024;

  private final String serviceName;
  private final String env;
  private final String version;
  private final String runtimeId;
  private final BatchUploader symbolUploader;
  private final int maxPayloadSize;
  private final long maxQueuedScopesSize;
  // uploadId is shared by all batches uploaded by this sink. The backend uses
  // it to group batches belonging to the same logical upload.
  private final String uploadId = RandomUtils.randomUUID().toString();
  private final AtomicLong batchNum = new AtomicLong(0);
  // jar scopes are kept pre-serialized as JSON to reduce memory footprint while enqueued
  private final BlockingQueue<String> scopes = new ArrayBlockingQueue<>(CAPACITY);
  // cumulative length of the serialized scopes currently in the queue
  private final AtomicLong queuedScopesSize = new AtomicLong(0);
  private final Stats stats = new Stats();
  private final boolean isCompressed;

  public SymbolSink(Config config) {
    this(
        config,
        new BatchUploader("SymDB", config, config.getFinalDebuggerSymDBUrl(), RETRY_POLICY),
        MAX_SYMDB_UPLOAD_SIZE);
  }

  SymbolSink(Config config, BatchUploader symbolUploader, int maxPayloadSize) {
    this(config, symbolUploader, maxPayloadSize, defaultMaxQueuedScopesSize());
  }

  SymbolSink(
      Config config, BatchUploader symbolUploader, int maxPayloadSize, long maxQueuedScopesSize) {
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.env = TagsHelper.sanitize(config.getEnv());
    this.version = TagsHelper.sanitize(config.getVersion());
    this.runtimeId = config.getRuntimeId();
    this.symbolUploader = symbolUploader;
    this.maxPayloadSize = maxPayloadSize;
    this.maxQueuedScopesSize = maxQueuedScopesSize;
    this.isCompressed = config.isSymbolDatabaseCompressed();
  }

  /** Budget for the queued scopes: 1/64 of the max heap, clamped between 4MB and 128MB. */
  static long defaultMaxQueuedScopesSize() {
    long budget = Runtime.getRuntime().maxMemory() / 64;
    return Math.max(MIN_QUEUED_SCOPES_SIZE, Math.min(MAX_QUEUED_SCOPES_SIZE, budget));
  }

  public void stop() {
    symbolUploader.shutdown();
  }

  public void addScope(Scope jarScope) {
    String serializedScope = SCOPE_ADAPTER.toJson(jarScope);
    stats.updateStats(jarScope.getScopes() != null ? jarScope.getScopes().size() : 0, 0);
    // account for the size before enqueuing so a concurrent drain never makes it negative
    long queuedSize = queuedScopesSize.addAndGet(serializedScope.length());
    boolean added = scopes.offer(serializedScope);
    int retries = 10;
    while (!added) {
      // Q is full, flushing synchronously
      flush();
      added = scopes.offer(serializedScope);
      retries--;
      if (retries < 0) {
        queuedScopesSize.addAndGet(-serializedScope.length());
        throw new IllegalStateException(
            "Scope cannot be enqueued after 10 retries: " + jarScope.getName());
      }
    }
    if (queuedSize >= maxQueuedScopesSize) {
      LOGGER.debug(
          "Queued scopes size reached limit: {}/{}, flushing", queuedSize, maxQueuedScopesSize);
      flush();
    }
  }

  public void flush() {
    if (scopes.isEmpty()) {
      return;
    }
    List<String> scopesToSerialize = new ArrayList<>();
    // ArrayBlockingQueue makes drainTo atomic, so it is safe to call flush from different and
    // concurrent threads
    scopes.drainTo(scopesToSerialize);
    long drainedSize = 0;
    for (String scope : scopesToSerialize) {
      drainedSize += scope.length();
    }
    queuedScopesSize.addAndGet(-drainedSize);
    // concurrent calls to flush can result in empty scope to send, we don't want to send empty
    if (scopesToSerialize.isEmpty()) {
      return;
    }
    serializeAndUpload(scopesToSerialize);
  }

  private void serializeAndUpload(List<String> scopesToSerialize) {
    // Determine the batch number once so the attachment body and the EvP event
    // message agree on it.
    long currentBatch = batchNum.incrementAndGet();
    try {
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(2 * 1024 * 1024);
      try (OutputStream outputStream =
          isCompressed ? new GZIPOutputStream(byteArrayOutputStream) : byteArrayOutputStream) {
        BufferedSink sink = Okio.buffer(Okio.sink(outputStream));
        SERVICE_VERSION_ADAPTER.toJson(
            sink,
            new ServiceVersion(
                serviceName,
                env,
                version,
                "JAVA",
                scopesToSerialize,
                uploadId,
                currentBatch,
                false /* isFinal */));
        sink.flush();
      }
      doUpload(scopesToSerialize, byteArrayOutputStream.toByteArray(), isCompressed, currentBatch);
    } catch (IOException e) {
      LOGGER.debug("Error serializing scopes", e);
    }
  }

  private void doUpload(
      List<String> scopesToSerialize, byte[] payload, boolean isCompressed, long currentBatch) {
    if (payload.length > maxPayloadSize) {
      LOGGER.warn(
          "Payload is too big: {}/{} isCompressed={}",
          payload.length,
          maxPayloadSize,
          isCompressed);
      splitAndSend(scopesToSerialize);
      return;
    }
    stats.updateStats(0, payload.length);
    LOGGER.debug("SymbolSink stats: {}", stats);
    LOGGER.debug(
        "Sending {} jar scopes size={} isCompressed={} uploadId={} batchNum={}",
        scopesToSerialize.size(),
        payload.length,
        isCompressed,
        uploadId,
        currentBatch);
    String fileName = "file.json";
    MediaType mediaType = APPLICATION_JSON;
    if (isCompressed) {
      fileName = "file.gz";
      mediaType = APPLICATION_GZIP;
    }
    BatchUploader.MultiPartContent event = buildEvent(currentBatch, payload.length);
    symbolUploader.uploadAsMultipart(
        "", event, new BatchUploader.MultiPartContent(payload, "file", fileName, mediaType));
  }

  private BatchUploader.MultiPartContent buildEvent(long currentBatch, int attachmentSize) {
    byte[] eventContent =
        Strings.format(
                EVENT_FORMAT,
                serviceName,
                version,
                runtimeId,
                uploadId.toString(),
                currentBatch,
                attachmentSize)
            .getBytes(StandardCharsets.UTF_8);
    return new BatchUploader.MultiPartContent(
        eventContent, "event", "event.json", APPLICATION_JSON);
  }

  private static byte[] compressPayload(byte[] jsonBytes) {
    // usual compression factor 40:1 for those json payload, so we are preallocating 1/40
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(jsonBytes.length / 40);
    try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
      gzipOutputStream.write(jsonBytes);
    } catch (IOException ex) {
      LOGGER.error("Error compressing json", ex);
      return null;
    }
    LOGGER.debug(
        "Compressed payload from={} to={}", jsonBytes.length, byteArrayOutputStream.size());
    return byteArrayOutputStream.toByteArray();
  }

  /*
   * Try to split recursively the scopes to send in smaller chunks
   * first try by splitting the jar scopes, then by splitting the class scopes
   * stopped the recursion when the scopes are small enough to be sent or <2 classes in one scope
   */
  private void splitAndSend(List<String> scopesToSerialize) {
    if (scopesToSerialize.size() > 1) {
      // try to split by jar scopes: one scope per request
      if (scopesToSerialize.size() < BatchUploader.MAX_ENQUEUED_REQUESTS) {
        for (String scope : scopesToSerialize) {
          serializeAndUpload(Collections.singletonList(scope));
        }
      } else {
        // split the list of jar scope in 2 list jar scopes with half of the jar scopes
        int half = scopesToSerialize.size() / 2;
        List<String> firstHalf = scopesToSerialize.subList(0, half);
        List<String> secondHalf = scopesToSerialize.subList(half, scopesToSerialize.size());
        LOGGER.debug("split jar scope list in 2: {} and {}", firstHalf.size(), secondHalf.size());
        serializeAndUpload(firstHalf);
        serializeAndUpload(secondHalf);
      }
    } else {
      // rare path: deserialize the jar scope to be able to split it by class scopes
      Scope jarScope;
      try {
        jarScope = SCOPE_ADAPTER.fromJson(scopesToSerialize.get(0));
      } catch (IOException e) {
        LOGGER.debug("Error deserializing jar scope", e);
        return;
      }
      if (jarScope == null || jarScope.getScopes() == null) {
        LOGGER.debug(
            "No class scopes to send for jar scope {}",
            jarScope != null ? jarScope.getName() : null);
        return;
      }
      if (jarScope.getScopes().size() < 2) {
        LOGGER.warn(
            "Cannot split jar scope with less than 2 classes scope: {}", jarScope.getName());
        return;
      }
      // split the jar scope in 2 jar scopes with half of the class scopes
      int half = jarScope.getScopes().size() / 2;
      List<Scope> firstHalf = jarScope.getScopes().subList(0, half);
      List<Scope> secondHalf = jarScope.getScopes().subList(half, jarScope.getScopes().size());
      LOGGER.debug(
          "split jar scope {} in 2 jar scopes: {} and {}",
          jarScope.getName(),
          firstHalf.size(),
          secondHalf.size());
      splitAndSend(
          Arrays.asList(
              SCOPE_ADAPTER.toJson(createJarScope(jarScope.getName(), firstHalf)),
              SCOPE_ADAPTER.toJson(createJarScope(jarScope.getName(), secondHalf))));
    }
  }

  private static Scope createJarScope(String jarName, List<Scope> classScopes) {
    return Scope.builder(ScopeType.JAR, jarName, 0, 0).name(jarName).scopes(classScopes).build();
  }

  public HttpUrl getUrl() {
    return symbolUploader.getUrl();
  }

  public Stats getStats() {
    return stats;
  }

  public static class Stats {
    private long totalClassScopes;
    private long totalSize;

    public long getTotalClassScopes() {
      return totalClassScopes;
    }

    public long getTotalSize() {
      return totalSize;
    }

    void updateStats(long classScopes, long size) {
      totalClassScopes += classScopes;
      totalSize += size;
    }

    @Override
    public String toString() {
      return "Stats{" + "totalClassScopes=" + totalClassScopes + ", totalSize=" + totalSize + '}';
    }
  }
}
