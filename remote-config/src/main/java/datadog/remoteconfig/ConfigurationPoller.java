package datadog.remoteconfig;

import com.squareup.moshi.Moshi;
import datadog.remoteconfig.ConfigurationChangesListener.PollingRateHinter;
import datadog.remoteconfig.tuf.FeaturesConfig;
import datadog.remoteconfig.tuf.InstantJsonAdapter;
import datadog.remoteconfig.tuf.RawJsonAdapter;
import datadog.remoteconfig.tuf.RemoteConfigRequest.CachedTargetFile;
import datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.ClientState;
import datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.ClientState.ConfigState;
import datadog.remoteconfig.tuf.RemoteConfigResponse;
import datadog.trace.api.Config;
import datadog.trace.relocate.api.RatelimitedLogger;
import datadog.trace.util.AgentTaskScheduler;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles polling debugger configuration from datadog agent/Remote Configuration */
public class ConfigurationPoller
    implements AgentTaskScheduler.Target<ConfigurationPoller>, PollingRateHinter {
  private static final Logger log = LoggerFactory.getLogger(ConfigurationPoller.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;

  private final OkHttpClient httpClient;
  private final RatelimitedLogger ratelimitedLogger;
  private final PollerScheduler scheduler;
  private final long maxPayloadSize;
  private final PollerRequestFactory requestFactory;
  private final RemoteConfigResponse.Factory responseFactory;

  /** Map from product name to deserializer/listener. */
  private final Map<String, DeserializerAndListener<?>> listeners = new HashMap<>();

  private final Map<File, DeserializerAndListener<?>> fileListeners = new HashMap<>();
  private final Map<String, DeserializerAndListener<?>> featureListeners = new HashMap<>();
  private FeaturesConfig lastFeaturesConfig;
  private final ClientState nextClientState = new ClientState();
  private final Map<String /*cfg key*/, CachedTargetFile> cachedTargetFiles = new HashMap<>();
  private final AtomicInteger startCount = new AtomicInteger(0);
  private final Moshi moshi;

  private Duration durationHint;

  public ConfigurationPoller(
      Config config,
      String tracerVersion,
      String containerId,
      String configUrl,
      OkHttpClient client) {
    this(config, tracerVersion, containerId, configUrl, client, AgentTaskScheduler.INSTANCE);
  }

  // for testing
  public ConfigurationPoller(
      Config config,
      String tracerVersion,
      String containerId,
      String configUrl,
      OkHttpClient httpClient,
      AgentTaskScheduler taskScheduler) {
    if (configUrl == null || configUrl.length() == 0) {
      throw new IllegalArgumentException("Remote config url is empty");
    }

    this.scheduler = new PollerScheduler(config, this, taskScheduler);
    log.debug(
        "Started remote config poller every {} ms with target url {}",
        scheduler.getInitialPollInterval(),
        configUrl);
    this.ratelimitedLogger =
        new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES);
    this.maxPayloadSize = config.getRemoteConfigMaxPayloadSizeBytes();
    this.moshi =
        new Moshi.Builder()
            .add(Instant.class, new InstantJsonAdapter())
            .add(ByteString.class, new RawJsonAdapter())
            .build();
    this.requestFactory =
        new PollerRequestFactory(config, tracerVersion, containerId, configUrl, moshi);
    this.responseFactory = new RemoteConfigResponse.Factory(moshi);
    this.httpClient = httpClient;
  }

  public synchronized <T> void addListener(
      Product product,
      ConfigurationDeserializer<T> deserializer,
      ConfigurationChangesListener<T> listener) {
    this.listeners.put(product.name(), new DeserializerAndListener<>(deserializer, listener));
  }

  public synchronized void removeListener(Product product) {
    this.listeners.remove(product.name());
  }

  public <T> void addFeaturesListener(
      String name,
      ConfigurationDeserializer<T> deserializer,
      ConfigurationChangesListener<T> listener) {

    DeserializerAndListener<T> dl = new DeserializerAndListener<>(deserializer, listener);
    byte[] productFeaturesByteArray;
    synchronized (this) {
      this.featureListeners.put(name, dl);
      if (!listeners.containsKey(Product.FEATURES.name())) {
        addListener(
            Product.FEATURES,
            new FeaturesConfig.FeaturesConfigDeserializer(moshi),
            this::featuresChangeListener);
      }

      // if we already have some saved features, call listener on them
      if (this.lastFeaturesConfig == null) {
        return;
      }
      productFeaturesByteArray = this.lastFeaturesConfig.getProductFeaturesByteArray(name);
    }

    if (productFeaturesByteArray != null) {
      try {
        dl.deserializeAndAccept(name, productFeaturesByteArray, PollingRateHinter.NOOP);
      } catch (RuntimeException | IOException e) {
        log.warn("Error applying features for {}", name, e);
      }
    }
  }

  public synchronized void removeFeaturesListener(String name) {
    this.featureListeners.remove(name);
    if (this.featureListeners.isEmpty()) {
      removeListener(Product.FEATURES);
    }
  }

  public synchronized <T> void addFileListener(
      File file,
      ConfigurationDeserializer<T> deserializer,
      ConfigurationChangesListener<T> listener) {
    this.fileListeners.put(file, new DeserializerAndListener<>(deserializer, listener));
  }

  @Override
  public ConfigurationPoller get() {
    return this;
  }

  public void start() {
    int prevCount = this.startCount.getAndIncrement();
    if (prevCount == 0) {
      scheduler.start();
    }
  }

  public void stop() {
    int newCount = this.startCount.decrementAndGet();
    if (newCount == 0) {
      scheduler.stop();
    }
  }

  synchronized void poll(ConfigurationPoller poller) {
    for (Map.Entry<File, DeserializerAndListener<?>> e : this.fileListeners.entrySet()) {
      loadFromFile(e.getKey(), e.getValue());
    }

    if (!this.listeners.isEmpty()) {
      try {
        sendRequest(this::handleAgentResponse);
      } catch (IOException | RuntimeException ex) {
        ExceptionHelper.rateLimitedLogException(
            ratelimitedLogger,
            log,
            ex,
            "Failed to poll remote configuration from {}",
            requestFactory.url.url().toString());
      }
    }
  }

  private Response fetchConfiguration() throws IOException {
    Request request =
        this.requestFactory.newConfigurationRequest(
            getSubscribedProductNames(), this.nextClientState, this.cachedTargetFiles.values());
    Call call = this.httpClient.newCall(request);
    return call.execute();
  }

  private Collection<String> getSubscribedProductNames() {
    return this.listeners.keySet();
  }

  void sendRequest(Consumer<ResponseBody> responseBodyConsumer) throws IOException {
    try (Response response = fetchConfiguration()) {
      ResponseBody body = response.body();
      if (response.isSuccessful()) {
        if (body == null) {
          ratelimitedLogger.warn("No body content while retrieving remote configuration");
          return;
        }
        responseBodyConsumer.accept(body);
        return;
      }
      // Retrieve body content for detailed error messages
      if (body != null) {
        try {
          ratelimitedLogger.warn(
              "Failed to retrieve remote configuration: unexpected response code {} {} {}",
              response.message(),
              response.code(),
              body.string());
        } catch (IOException ex) {
          ExceptionHelper.rateLimitedLogException(
              ratelimitedLogger, log, ex, "Error while getting error message body");
        }
      } else {
        ratelimitedLogger.warn(
            "Failed to retrieve remote configuration: unexpected response code {} {}",
            response.message(),
            response.code());
      }
    }
  }

  private void handleAgentResponse(ResponseBody body) {
    int successes = 0, failures = 0;

    List<String> inspectedConfigurationKeys = new ArrayList<>();
    RemoteConfigResponse fleetResponse;

    try (InputStream inputStream = new SizeCheckedInputStream(body.byteStream(), maxPayloadSize)) {
      Optional<RemoteConfigResponse> maybeFleetResp;
      maybeFleetResp = this.responseFactory.fromInputStream(inputStream);
      if (!maybeFleetResp.isPresent()) {
        log.debug("No configuration changes");
        return;
      }

      fleetResponse = maybeFleetResp.get();
    } catch (Exception e) {
      // no error can be reported, as we don't have the data client.state.targets_version avail
      ratelimitedLogger.warn("Error parsing remote config response", e);
      return;
    }

    if (log.isDebugEnabled() && fleetResponse.getTargetsSigned() != null) {
      log.debug(
          "Got configuration with targets version {}", fleetResponse.getTargetsSigned().version);
    }

    List<String> configsToApply = fleetResponse.getClientConfigs();
    String errorMessage = null;
    this.durationHint = null;
    for (String configKey : configsToApply) {
      // The spec specifies that everything should stop once there is a problem
      // applying one of the configurations. This would make the configurations
      // be applied or not depending solely on the (unspecified) iteration order, with
      // unpredictable results. We continue trying to process configurations after
      // we encounter an error. This is (arguably) in spec, as it corresponds
      // to a situation where the (unspecified) iteration order where the error
      // triggering config keys would be processed at the end.
      try {
        boolean res = processConfigKey(fleetResponse, configKey, inspectedConfigurationKeys, this);
        if (res) {
          successes++;
        } else {
          failures++;
        }
      } catch (Exception e) {
        this.ratelimitedLogger.warn("Error processing config key {}", configKey, e);
        failures++;
        errorMessage = e.getMessage();
      }
    }

    updateNextState(fleetResponse, inspectedConfigurationKeys, errorMessage);

    if (successes == 0 && failures > 0) {
      throw new RuntimeException(
          "None of the configuration data was successfully read and processed");
    }

    rescheduleBaseOnConfiguration(this.durationHint);
  }

  private DeserializerAndListener<?> extractDeserializerAndListenerFromKey(String configKey) {
    String productName = extractProductFromKey(configKey);
    if (productName == null) {
      throw new ReportableException("Cannot extract product from key " + configKey);
    }

    DeserializerAndListener<?> dl = this.listeners.get(productName);
    if (dl == null) {
      throw new ReportableException(
          "Told to handle config key "
              + configKey
              + ", but the product "
              + productName
              + " is not being handled");
    }

    return dl;
  }

  private boolean notifyConfigurationKeyRemoved(
      String configKey, PollingRateHinter pollingRateHinter) throws IOException {
    return extractDeserializerAndListenerFromKey(configKey)
        .deserializeAndAccept(configKey, null, pollingRateHinter);
  }

  private boolean processConfigKey(
      RemoteConfigResponse fleetResponse,
      String configKey,
      List<String> inspectedConfigurationKeys,
      PollingRateHinter pollingRateHinter) {
    // find right product from configKey
    DeserializerAndListener<?> dl = extractDeserializerAndListenerFromKey(configKey);

    // check if the hash of this configuration file actually changed
    CachedTargetFile cachedTargetFile = this.cachedTargetFiles.get(configKey);
    RemoteConfigResponse.Targets.ConfigTarget target = fleetResponse.getTarget(configKey);
    if (target == null) {
      throw new ReportableException(
          "Told to apply config for "
              + configKey
              + " but no corresponding entry exists in targets.targets_signed.targets");
    }
    if (cachedTargetFile != null && cachedTargetFile.hashesMatch(target.hashes)) {
      log.debug("No change in configuration for key {}", configKey);
      inspectedConfigurationKeys.add(configKey);
      return true;
    }

    // fetch the content
    Optional<byte[]> maybeFileContent = fleetResponse.getFileContents(configKey);
    if (!maybeFileContent.isPresent()) {
      if (this.cachedTargetFiles.containsKey(configKey)) {
        throw new ReportableException(
            "Told to apply config "
                + configKey
                + " but content not present even though hash differs from that of 'cached file'");
      }

      throw new ReportableException("No content for " + configKey);
    }

    // add to inspectedConfigurationKeys so it's reported on config_states
    // (even if deserializing or applying config fails)
    inspectedConfigurationKeys.add(configKey);

    // deserialize and apply config
    byte[] fileContent = maybeFileContent.get();

    try {
      log.debug("Applying configuration for {}", configKey);
      return dl.deserializeAndAccept(configKey, fileContent, pollingRateHinter);
    } catch (IOException | RuntimeException ex) {
      ratelimitedLogger.warn("Error handling configuration for " + configKey, ex);
      return false;
    }
  }

  /**
   * @param fleetResponse the response the agent sent
   * @param inspectedConfigKeys the configurations that were applied to the tracer either a) in this
   *     iteration or b) in a previous request, and the configuration has not changed. If there is
   *     an error that resulted in no config keys even be inspected then the config states and
   *     cached files are emptied.
   * @param errorMessage a procedural error that occurred during the update; it does not include
   *     errors during registered deserializers and config listeners. If there is an error, the
   *     targets_version in client.state is not updated (but the rest is).
   */
  private void updateNextState(
      RemoteConfigResponse fleetResponse, List<String> inspectedConfigKeys, String errorMessage) {
    RemoteConfigResponse.Targets.TargetsSigned targetsSigned = fleetResponse.getTargetsSigned();
    List<ConfigState> configStates = this.nextClientState.configStates;

    int numInspectedConfigs = inspectedConfigKeys.size();

    if (targetsSigned == null || targetsSigned.targets == null) {
      ratelimitedLogger.warn("No targets in response; can't properly " + "set config_states");
      numInspectedConfigs = 0;
    }

    // Do config_states
    int csi = 0; // config_states index
    for (int i = 0; i < numInspectedConfigs; i++) {
      String configKey = inspectedConfigKeys.get(i);
      RemoteConfigResponse.Targets.ConfigTarget target = targetsSigned.targets.get(configKey);

      if (target == null || target.custom == null) {
        ratelimitedLogger.warn(
            "Target for {} does not exist or does not define 'custom' field", configKey);
        continue;
      }
      long version = target.custom.version;

      ConfigState configState;
      if (csi >= configStates.size()) {
        configState = new ConfigState();
        configStates.add(configState);
      } else {
        // recycle objects if possible
        configState = configStates.get(csi);
      }
      csi++;

      configState.setState(configKey, version, extractProductFromKey(configKey));
    }
    // remove excess configStates
    int numConfigStates = configStates.size();
    for (int i = csi; i < numConfigStates; i++) {
      configStates.remove(configStates.size() - 1);
    }

    long newTargetsVersion = targetsSigned.version;
    this.nextClientState.setState(
        // if there was an error, we did not apply the configurations fully
        // the system tests expect here the targets version not to be updated
        errorMessage == null ? newTargetsVersion : this.nextClientState.targetsVersion,
        configStates,
        errorMessage,
        targetsSigned.custom != null ? targetsSigned.custom.opaqueBackendState : null);

    // Do cached_target_files
    for (String configKey : inspectedConfigKeys) {
      CachedTargetFile curCTF = this.cachedTargetFiles.get(configKey);
      RemoteConfigResponse.Targets.ConfigTarget target = targetsSigned.targets.get(configKey);
      // configuration is not applied without correct hashes, so this is guaranteedly non-null
      assert target != null && target.hashes != null;

      if (curCTF != null) {
        if (curCTF.length != target.length || !curCTF.hashesMatch(target.hashes)) {
          curCTF = null;
        }
      }

      if (curCTF == null) {
        CachedTargetFile newCTF = new CachedTargetFile(configKey, target.length, target.hashes);
        this.cachedTargetFiles.put(configKey, newCTF);
      }
    }

    // remove cachedTargetFiles for the pulled configurations
    Iterator<String> cachedConfigKeysIter = this.cachedTargetFiles.keySet().iterator();
    while (cachedConfigKeysIter.hasNext()) {
      String configKey = cachedConfigKeysIter.next();
      if (!inspectedConfigKeys.contains(configKey)) {
        cachedConfigKeysIter.remove();
        try {
          log.debug("Removing configuration for {}", configKey);
          notifyConfigurationKeyRemoved(configKey, this);
        } catch (IOException | RuntimeException ex) {
          ratelimitedLogger.warn("Error handling configuration for " + configKey, ex);
        }
      }
    }
  }

  private static final Pattern EXTRACT_PRODUCT_REGEX =
      Pattern.compile("[^/]+(?:/\\d+)?/([^/]+)/[^/]+/config");

  private static String extractProductFromKey(String configKey) {
    Matcher matcher = EXTRACT_PRODUCT_REGEX.matcher(configKey);
    if (!matcher.matches()) {
      throw new ReportableException("Not a valid config key: " + configKey);
    }
    return matcher.group(1);
  }

  private void rescheduleBaseOnConfiguration(Duration hint) {
    if (hint == null) {
      return;
    }

    scheduler.reschedule(hint.toMillis());
  }

  private void loadFromFile(File file, DeserializerAndListener<?> deserializerAndListener) {
    log.debug("Loading configuration from file {}", file);

    try (InputStream inputStream =
        new SizeCheckedInputStream(new FileInputStream(file), maxPayloadSize)) {
      byte[] buffer = new byte[4096];
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream(4096);
      int bytesRead;
      do {
        bytesRead = inputStream.read(buffer);
        if (bytesRead > -1) {
          outputStream.write(buffer, 0, bytesRead);
        }
      } while (bytesRead > -1);

      boolean res =
          deserializerAndListener.deserializeAndAccept(
              file.getAbsolutePath(), outputStream.toByteArray(), PollingRateHinter.NOOP);
      if (!res) {
        ratelimitedLogger.warn("Failed reading or applying configuration from {}", file);
      } else {
        log.debug("Loaded configuration from file {}", file);
      }
    } catch (IOException | RuntimeException ex) {
      ExceptionHelper.rateLimitedLogException(
          ratelimitedLogger, log, ex, "Unable to load config file: {}.", file);
    }
  }

  // marked as synchronized only to satisfy spotbugs,
  // because this method is only called from synchronized methods anyway
  private synchronized boolean featuresChangeListener(
      String configKey, FeaturesConfig fconfig, PollingRateHinter hinter) {
    if (fconfig == null) {
      log.warn("Features configuration was pulled, which is unexpected");
      return true;
    }

    this.lastFeaturesConfig = fconfig;

    for (String product : fconfig.getProducts()) {
      DeserializerAndListener<?> dl = this.featureListeners.get(product);
      if (dl == null) {
        log.debug("Not interested in features for {}", product);
        continue;
      }

      try {
        byte[] productFeaturesByteArray = fconfig.getProductFeaturesByteArray(product);
        dl.deserializeAndAccept(product, productFeaturesByteArray, hinter);
      } catch (IOException | RuntimeException e) {
        ratelimitedLogger.warn("Error processing features for {}", product);
      }
    }
    return true;
  }

  @Override
  public void suggestPollingRate(Duration duration) {
    if (durationHint == null) {
      durationHint = duration;
    } else if (duration.compareTo(durationHint) < 0) {
      durationHint = duration;
    }
  }

  private static class DeserializerAndListener<T> {
    final ConfigurationDeserializer<T> deserializer;
    final ConfigurationChangesListener<T> listener;

    DeserializerAndListener(
        ConfigurationDeserializer<T> deserializer, ConfigurationChangesListener<T> listener) {
      this.deserializer = deserializer;
      this.listener = listener;
    }

    boolean deserializeAndAccept(String configKey, byte[] bytes, PollingRateHinter hinter)
        throws IOException {
      T configuration = null;

      if (bytes != null) {
        configuration = this.deserializer.deserialize(bytes);
        // ensure deserializer return a value.
        if (configuration == null) {
          throw new RuntimeException("Configuration deserializer didn't provide a configuration");
        }
      }

      return this.listener.accept(configKey, configuration, hinter);
    }
  }

  /* An exception that should be reported in client.state.error */
  public static class ReportableException extends RuntimeException {
    public ReportableException(String message) {
      super(message);
    }
  }
}
