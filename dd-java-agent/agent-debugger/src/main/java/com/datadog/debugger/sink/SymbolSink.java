package com.datadog.debugger.sink;

import com.datadog.debugger.symbol.Scope;
import com.datadog.debugger.symbol.ServiceVersion;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.ExceptionHelper;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Config;
import datadog.trace.util.TagsHelper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolSink {

  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolSink.class);
  private static final int CAPACITY = 1024;
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
  private final BlockingQueue<ServiceVersion> scopes = new ArrayBlockingQueue<>(CAPACITY);
  private final Stats stats = new Stats();

  public SymbolSink(Config config) {
    this(config, new BatchUploader(config, config.getFinalDebuggerSymDBUrl()));
  }

  SymbolSink(Config config, BatchUploader symbolUploader) {
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.env = config.getEnv();
    this.version = config.getVersion();
    this.symbolUploader = symbolUploader;
    byte[] eventContent =
        String.format(
                EVENT_FORMAT, TagsHelper.sanitize(config.getServiceName()), config.getRuntimeId())
            .getBytes(StandardCharsets.UTF_8);
    this.event = new BatchUploader.MultiPartContent(eventContent, "event", "event.json");
  }

  public boolean addScope(Scope jarScope) {
    ServiceVersion serviceVersion =
        new ServiceVersion(serviceName, env, version, "JAVA", Collections.singletonList(jarScope));
    return scopes.offer(serviceVersion);
  }

  public void flush() {
    if (scopes.isEmpty()) {
      return;
    }
    List<ServiceVersion> scopesToSerialize = new ArrayList<>();
    scopes.drainTo(scopesToSerialize);
    LOGGER.debug("Sending {} scopes", scopesToSerialize.size());
    for (ServiceVersion serviceVersion : scopesToSerialize) {
      try {
        String json = SERVICE_VERSION_ADAPTER.toJson(serviceVersion);
        LOGGER.debug(
            "Sending scope: {}, size={}",
            serviceVersion.getScopes().get(0).getName(),
            json.length());
        List<Scope> classScopes = serviceVersion.getScopes().get(0).getScopes();
        int classScopeCount = classScopes != null ? classScopes.size() : 0;
        stats.updateStats(classScopeCount, json.length());
        symbolUploader.uploadAsMultipart(
            "",
            event,
            new BatchUploader.MultiPartContent(
                json.getBytes(StandardCharsets.UTF_8), "file", "file.json"));
      } catch (Exception e) {
        ExceptionHelper.logException(LOGGER, e, "Error during scope serialization:");
      }
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
