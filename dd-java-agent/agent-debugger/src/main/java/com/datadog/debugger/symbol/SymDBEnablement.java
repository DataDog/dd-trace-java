package com.datadog.debugger.symbol;

import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.util.AgentTaskScheduler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymDBEnablement implements ProductListener {
  private static final Logger LOGGER = LoggerFactory.getLogger(SymDBEnablement.class);
  private static final JsonAdapter<SymDbRemoteConfigRecord> SYM_DB_JSON_ADAPTER =
      MoshiHelper.createMoshiConfig().adapter(SymDbRemoteConfigRecord.class);
  private static final String SYM_DB_RC_KEY = "symDb";
  private static final int READ_BUFFER_SIZE = 4096;
  private static final int CLASSFILE_BUFFER_SIZE = 8192;

  private final Instrumentation instrumentation;
  private final Config config;
  private final SymbolAggregator symbolAggregator;
  private final AtomicBoolean starting = new AtomicBoolean();
  private SymbolExtractionTransformer symbolExtractionTransformer;
  private final ClassNameFilter classNameFilter;
  private volatile long lastUploadTimestamp;

  public SymDBEnablement(
      Instrumentation instrumentation,
      Config config,
      SymbolAggregator symbolAggregator,
      ClassNameFilter classNameFilter) {
    this.instrumentation = instrumentation;
    this.config = config;
    this.symbolAggregator = symbolAggregator;
    this.classNameFilter = classNameFilter;
  }

  @Override
  public void accept(ConfigKey configKey, byte[] content, PollingRateHinter pollingRateHinter)
      throws IOException {
    if (configKey.getConfigId().equals(SYM_DB_RC_KEY)) {
      SymDbRemoteConfigRecord symDb = deserializeSymDb(content);
      if (symDb.isUploadSymbols()) {
        // can be long, make it async
        AgentTaskScheduler.get().execute(this::startSymbolExtraction);
      } else {
        stopSymbolExtraction();
      }
    } else {
      LOGGER.debug("unsupported configuration id {}", configKey.getConfigId());
    }
  }

  @Override
  public void remove(ConfigKey configKey, PollingRateHinter pollingRateHinter) throws IOException {
    if (configKey.getConfigId().equals(SYM_DB_RC_KEY)) {
      stopSymbolExtraction();
    }
  }

  @Override
  public void commit(PollingRateHinter pollingRateHinter) {}

  private static SymDbRemoteConfigRecord deserializeSymDb(byte[] content) throws IOException {
    return SYM_DB_JSON_ADAPTER.fromJson(
        Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
  }

  public void stopSymbolExtraction() {
    LOGGER.debug("Stopping symbol extraction.");
    if (symbolExtractionTransformer != null) {
      instrumentation.removeTransformer(symbolExtractionTransformer);
      symbolExtractionTransformer = null;
    }
  }

  long getLastUploadTimestamp() {
    return lastUploadTimestamp;
  }

  public void startSymbolExtraction() {
    if (!starting.compareAndSet(false, true)) {
      return;
    }
    try {
      LOGGER.debug("Starting symbol extraction...");
      if (lastUploadTimestamp > 0) {
        LOGGER.debug(
            "Last upload was on {}",
            LocalDateTime.ofInstant(
                Instant.ofEpochMilli(lastUploadTimestamp), ZoneId.systemDefault()));
        return;
      }
      try {
        symbolExtractionTransformer =
            new SymbolExtractionTransformer(symbolAggregator, classNameFilter);
        instrumentation.addTransformer(symbolExtractionTransformer);
        SymDBReport symDBReport = new BasicSymDBReport();
        extractSymbolForLoadedClasses(symDBReport);
        symDBReport.report();
        lastUploadTimestamp = System.currentTimeMillis();
      } catch (Throwable ex) {
        // catch all Throwables because LinkageError is possible (duplicate class definition)
        LOGGER.debug("Error during symbol extraction: ", ex);
      }
    } finally {
      starting.set(false);
    }
  }

  private void extractSymbolForLoadedClasses(SymDBReport symDBReport) {
    Class<?>[] classesToExtract;
    try {
      classesToExtract =
          Arrays.stream(instrumentation.getAllLoadedClasses())
              .filter(clazz -> !classNameFilter.isExcluded(clazz.getTypeName()))
              .filter(instrumentation::isModifiableClass)
              .toArray(Class<?>[]::new);
    } catch (Throwable ex) {
      LOGGER.debug("Failed to get all loaded classes", ex);
      return;
    }
    byte[] buffer = new byte[READ_BUFFER_SIZE];
    ByteArrayOutputStream baos = new ByteArrayOutputStream(CLASSFILE_BUFFER_SIZE);
    for (Class<?> clazz : classesToExtract) {
      Path jarPath;
      try {
        jarPath = JarScanner.extractJarPath(clazz, symDBReport);
      } catch (URISyntaxException e) {
        LOGGER.debug("Failed to extract jar path for class {}", clazz.getTypeName(), e);
        continue;
      }
      if (jarPath == null) {
        continue;
      }
      if (!Files.exists(jarPath)) {
        symDBReport.addMissingJar(jarPath.toString());
        continue;
      }
      try {
        symbolAggregator.scanJar(symDBReport, jarPath, baos, buffer);
      } catch (Exception ex) {
        LOGGER.debug("Failed to scan jar {}", jarPath, ex);
      }
    }
  }
}
