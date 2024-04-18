package com.datadog.debugger.symbol;

import static com.datadog.debugger.symbol.JarScanner.trimPrefixes;

import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.Strings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymDBEnablement implements ProductListener {
  private static final Logger LOGGER = LoggerFactory.getLogger(SymDBEnablement.class);
  private static final Pattern COMMA_PATTERN = Pattern.compile(",");
  private static final JsonAdapter<SymDbRemoteConfigRecord> SYM_DB_JSON_ADAPTER =
      MoshiHelper.createMoshiConfig().adapter(SymDbRemoteConfigRecord.class);
  private static final String SYM_DB_RC_KEY = "symDb";
  private static final int READ_BUFFER_SIZE = 4096;
  private static final int CLASSFILE_BUFFER_SIZE = 8192;

  private final Instrumentation instrumentation;
  private final Config config;
  private final SymbolAggregator symbolAggregator;
  private SymbolExtractionTransformer symbolExtractionTransformer;
  private final ClassNameFiltering classNameFiltering;
  private volatile long lastUploadTimestamp;
  private final AtomicBoolean starting = new AtomicBoolean();

  public SymDBEnablement(
      Instrumentation instrumentation,
      Config config,
      SymbolAggregator symbolAggregator,
      ClassNameFiltering classNameFiltering) {
    this.instrumentation = instrumentation;
    this.config = config;
    this.symbolAggregator = symbolAggregator;
    this.classNameFiltering = classNameFiltering;
  }

  @Override
  public void accept(
      ParsedConfigKey configKey,
      byte[] content,
      ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {
    if (configKey.getConfigId().equals(SYM_DB_RC_KEY)) {
      SymDbRemoteConfigRecord symDb = deserializeSymDb(content);
      if (symDb.isUploadSymbols()) {
        // can be long, make it async
        AgentTaskScheduler.INSTANCE.execute(this::startSymbolExtraction);
      } else {
        stopSymbolExtraction();
      }
    } else {
      LOGGER.debug("unsupported configuration id {}", configKey.getConfigId());
    }
  }

  @Override
  public void remove(
      ParsedConfigKey configKey, ConfigurationChangesListener.PollingRateHinter pollingRateHinter)
      throws IOException {
    if (configKey.getConfigId().equals(SYM_DB_RC_KEY)) {
      stopSymbolExtraction();
    }
  }

  @Override
  public void commit(ConfigurationChangesListener.PollingRateHinter pollingRateHinter) {}

  private static SymDbRemoteConfigRecord deserializeSymDb(byte[] content) throws IOException {
    return SYM_DB_JSON_ADAPTER.fromJson(
        Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
  }

  public void stopSymbolExtraction() {
    LOGGER.debug("Stopping symbol extraction.");
    instrumentation.removeTransformer(symbolExtractionTransformer);
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
      symbolAggregator.loadedClassesProcessStarted();
      try {
        symbolExtractionTransformer =
            new SymbolExtractionTransformer(symbolAggregator, classNameFiltering);
        instrumentation.addTransformer(symbolExtractionTransformer);
        extractSymbolForLoadedClasses();
        lastUploadTimestamp = System.currentTimeMillis();
      } catch (Throwable ex) {
        // catch all Throwables because LinkageError is possible (duplicate class definition)
        LOGGER.debug("Error during symbol extraction: ", ex);
      } finally {
        symbolAggregator.loadedClassesProcessEnded();
      }
    } finally {
      starting.set(false);
    }
  }

  private void extractSymbolForLoadedClasses() {
    Class<?>[] classesToExtract;
    try {
      classesToExtract =
          Arrays.stream(instrumentation.getAllLoadedClasses())
              .filter(clazz -> !classNameFiltering.isExcluded(clazz.getTypeName()))
              .filter(instrumentation::isModifiableClass)
              .toArray(Class<?>[]::new);
    } catch (Throwable ex) {
      LOGGER.debug("Failed to get all loaded classes", ex);
      return;
    }
    Set<String> alreadyScannedJars = new HashSet<>();
    byte[] buffer = new byte[READ_BUFFER_SIZE];
    ByteArrayOutputStream baos = new ByteArrayOutputStream(CLASSFILE_BUFFER_SIZE);
    for (Class<?> clazz : classesToExtract) {
      Path jarPath;
      try {
        jarPath = JarScanner.extractJarPath(clazz);
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
      if (jarPath == null) {
        continue;
      }
      if (!Files.exists(jarPath)) {
        continue;
      }
      if (alreadyScannedJars.contains(jarPath.toString())) {
        continue;
      }
      try {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
          jarFile.stream()
              .filter(jarEntry -> jarEntry.getName().endsWith(".class"))
              .filter(
                  jarEntry ->
                      !classNameFiltering.isExcluded(
                          Strings.getClassName(trimPrefixes(jarEntry.getName()))))
              .forEach(jarEntry -> parseJarEntry(jarEntry, jarFile, jarPath, baos, buffer));
        }
        alreadyScannedJars.add(jarPath.toString());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private void parseJarEntry(
      JarEntry jarEntry, JarFile jarFile, Path jarPath, ByteArrayOutputStream baos, byte[] buffer) {
    LOGGER.debug("parsing jarEntry class: {}", jarEntry.getName());
    try {
      InputStream inputStream = jarFile.getInputStream(jarEntry);
      int readBytes;
      baos.reset();
      while ((readBytes = inputStream.read(buffer)) != -1) {
        baos.write(buffer, 0, readBytes);
      }
      symbolAggregator.parseClass(jarEntry.getName(), baos.toByteArray(), jarPath.toString());
    } catch (IOException ex) {
      LOGGER.debug("Exception during parsing jarEntry class: {}", jarEntry.getName(), ex);
    }
  }
}
