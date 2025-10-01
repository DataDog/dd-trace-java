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
      MoshiHelper.createMoshiSymbol().adapter(ServiceVersion.class);
  private static final String EVENT_FORMAT =
      "{%n"
          + "\"ddsource\": \"dd_debugger\",%n"
          + "\"service\": \"%s\",%n"
          + "\"runtimeId\": \"%s\",%n"
          + "\"type\": \"symdb\"%n"
          + "}";
  static final int MAX_SYMDB_UPLOAD_SIZE = 50 * 1024 * 1024;

  private final String serviceName;
  private final String env;
  private final String version;
  private final BatchUploader symbolUploader;
  private final int maxPayloadSize;
  private final BatchUploader.MultiPartContent event;
  private final BlockingQueue<Scope> scopes = new ArrayBlockingQueue<>(CAPACITY);
  private final Stats stats = new Stats();
  private final boolean isCompressed;

  public SymbolSink(Config config) {
    this(
        config,
        new BatchUploader("SymDB", config, config.getFinalDebuggerSymDBUrl(), RETRY_POLICY),
        MAX_SYMDB_UPLOAD_SIZE);
  }

  SymbolSink(Config config, BatchUploader symbolUploader, int maxPayloadSize) {
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.env = TagsHelper.sanitize(config.getEnv());
    this.version = TagsHelper.sanitize(config.getVersion());
    this.symbolUploader = symbolUploader;
    this.maxPayloadSize = maxPayloadSize;
    this.isCompressed = config.isSymbolDatabaseCompressed();
    byte[] eventContent =
        String.format(
                EVENT_FORMAT, TagsHelper.sanitize(config.getServiceName()), config.getRuntimeId())
            .getBytes(StandardCharsets.UTF_8);
    this.event =
        new BatchUploader.MultiPartContent(eventContent, "event", "event.json", APPLICATION_JSON);
  }

  public void stop() {
    symbolUploader.shutdown();
  }

  public void addScope(Scope jarScope) {
    boolean added = scopes.offer(jarScope);
    int retries = 10;
    while (!added) {
      // Q is full, flushing synchronously
      flush();
      added = scopes.offer(jarScope);
      retries--;
      if (retries < 0) {
        throw new IllegalStateException("Scope cannot be enqueued after 10 retries" + jarScope);
      }
    }
  }

  public void flush() {
    if (scopes.isEmpty()) {
      return;
    }
    List<Scope> scopesToSerialize = new ArrayList<>();
    // ArrayBlockingQueue makes drainTo atomic, so it is safe to call flush from different and
    // concurrent threads
    scopes.drainTo(scopesToSerialize);
    // concurrent calls to flush can result in empty scope to send, we don't want to send empty
    if (scopesToSerialize.isEmpty()) {
      return;
    }
    serializeAndUpload(scopesToSerialize);
  }

  private void serializeAndUpload(List<Scope> scopesToSerialize) {
    try {
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(2 * 1024 * 1024);
      try (OutputStream outputStream =
          isCompressed ? new GZIPOutputStream(byteArrayOutputStream) : byteArrayOutputStream) {
        BufferedSink sink = Okio.buffer(Okio.sink(outputStream));
        SERVICE_VERSION_ADAPTER.toJson(
            sink, new ServiceVersion(serviceName, env, version, "JAVA", scopesToSerialize));
        sink.flush();
      }
      doUpload(scopesToSerialize, byteArrayOutputStream.toByteArray(), isCompressed);
    } catch (IOException e) {
      LOGGER.debug("Error serializing scopes", e);
    }
  }

  private void doUpload(List<Scope> scopesToSerialize, byte[] payload, boolean isCompressed) {
    if (payload.length > maxPayloadSize) {
      LOGGER.warn(
          "Payload is too big: {}/{} isCompressed={}",
          payload.length,
          maxPayloadSize,
          isCompressed);
      splitAndSend(scopesToSerialize);
      return;
    }
    updateStats(scopesToSerialize, payload.length);
    LOGGER.debug(
        "Sending {} jar scopes size={} isCompressed={}",
        scopesToSerialize.size(),
        payload.length,
        isCompressed);
    String fileName = "file.json";
    MediaType mediaType = APPLICATION_JSON;
    if (isCompressed) {
      fileName = "file.gz";
      mediaType = APPLICATION_GZIP;
    }
    symbolUploader.uploadAsMultipart(
        "", event, new BatchUploader.MultiPartContent(payload, "file", fileName, mediaType));
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
  private void splitAndSend(List<Scope> scopesToSerialize) {
    if (scopesToSerialize.size() > 1) {
      // try to split by jar scopes: one scope per request
      if (scopesToSerialize.size() < BatchUploader.MAX_ENQUEUED_REQUESTS) {
        for (Scope scope : scopesToSerialize) {
          serializeAndUpload(Collections.singletonList(scope));
        }
      } else {
        // split the list of jar scope in 2 list jar scopes with half of the jar scopes
        int half = scopesToSerialize.size() / 2;
        List<Scope> firstHalf = scopesToSerialize.subList(0, half);
        List<Scope> secondHalf = scopesToSerialize.subList(half, scopesToSerialize.size());
        LOGGER.debug("split jar scope list in 2: {} and {}", firstHalf.size(), secondHalf.size());
        serializeAndUpload(firstHalf);
        serializeAndUpload(secondHalf);
      }
    } else {
      Scope jarScope = scopesToSerialize.get(0);
      if (jarScope.getScopes() == null) {
        LOGGER.debug("No class scopes to send for jar scope {}", jarScope.getName());
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
              createJarScope(jarScope.getName(), firstHalf),
              createJarScope(jarScope.getName(), secondHalf)));
    }
  }

  private static Scope createJarScope(String jarName, List<Scope> classScopes) {
    return Scope.builder(ScopeType.JAR, jarName, 0, 0).name(jarName).scopes(classScopes).build();
  }

  private void updateStats(List<Scope> scopesToSerialize, long size) {
    int totalClasses = 0;
    for (Scope scope : scopesToSerialize) {
      totalClasses += scope.getScopes() != null ? scope.getScopes().size() : 0;
    }
    stats.updateStats(totalClasses, size);
    LOGGER.debug("SymbolSink stats: {}", stats);
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
