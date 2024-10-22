package com.datadog.debugger.sink;

import com.datadog.debugger.symbol.Scope;
import com.datadog.debugger.symbol.ServiceVersion;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Config;
import datadog.trace.util.TagsHelper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolSink {

  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolSink.class);
  static final int CAPACITY = 1024;
  private static final BatchUploader.RetryPolicy SYMBOL_RETRY_POLICY =
      new BatchUploader.RetryPolicy(10, 10);
  private static final JsonAdapter<ServiceVersion> SERVICE_VERSION_ADAPTER =
      MoshiHelper.createMoshiSymbol().adapter(ServiceVersion.class);
  private static final String EVENT_FORMAT =
      "{%n"
          + "\"ddsource\": \"dd_debugger\",%n"
          + "\"service\": \"%s\",%n"
          + "\"runtimeId\": \"%s\"%n"
          + "}";

  private final String serviceName;
  private final String env;
  private final String version;
  private final BatchUploader symbolUploader;
  private final BatchUploader.MultiPartContent event;
  private final BlockingQueue<Scope> scopes = new ArrayBlockingQueue<>(CAPACITY);
  private final Stats stats = new Stats();

  public SymbolSink(Config config) {
    this(config, new BatchUploader(config, config.getFinalDebuggerSymDBUrl(), SYMBOL_RETRY_POLICY));
  }

  SymbolSink(Config config, BatchUploader symbolUploader) {
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.env = TagsHelper.sanitize(config.getEnv());
    this.version = TagsHelper.sanitize(config.getVersion());
    this.symbolUploader = symbolUploader;
    byte[] eventContent =
        String.format(
                EVENT_FORMAT, TagsHelper.sanitize(config.getServiceName()), config.getRuntimeId())
            .getBytes(StandardCharsets.UTF_8);
    this.event = new BatchUploader.MultiPartContent(eventContent, "event", "event.json");
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
    String json =
        SERVICE_VERSION_ADAPTER.toJson(
            new ServiceVersion(serviceName, env, version, "JAVA", scopesToSerialize));
    LOGGER.debug("Sending {} jar scopes size={}", scopesToSerialize.size(), json.length());
    updateStats(scopesToSerialize, json);
    symbolUploader.uploadAsMultipart(
        "",
        event,
        new BatchUploader.MultiPartContent(
            json.getBytes(StandardCharsets.UTF_8), "file", "file.json"));
  }

  private void updateStats(List<Scope> scopesToSerialize, String json) {
    for (Scope scope : scopesToSerialize) {
      stats.updateStats(scope.getScopes() != null ? scope.getScopes().size() : 0, json.length());
    }
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
