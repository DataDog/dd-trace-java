package datadog.remoteconfig;

import static datadog.remoteconfig.ConfigurationChangesTypedListener.Builder.useDeserializer;
import static datadog.remoteconfig.PollingHinterNoop.NOOP;

import cafe.cryptography.curve25519.InvalidEncodingException;
import cafe.cryptography.ed25519.Ed25519PublicKey;
import cafe.cryptography.ed25519.Ed25519Signature;
import com.squareup.moshi.Moshi;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.remoteconfig.state.ProductState;
import datadog.remoteconfig.state.SimpleProductListener;
import datadog.remoteconfig.tuf.InstantJsonAdapter;
import datadog.remoteconfig.tuf.RawJsonAdapter;
import datadog.remoteconfig.tuf.RemoteConfigRequest;
import datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.ClientState;
import datadog.remoteconfig.tuf.RemoteConfigRequest.ClientInfo.ClientState.ConfigState;
import datadog.remoteconfig.tuf.RemoteConfigResponse;
import datadog.trace.api.Config;
import datadog.trace.relocate.api.RatelimitedLogger;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.SizeCheckedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles polling debugger configuration from datadog agent/Remote Configuration */
public class DefaultConfigurationPoller
    implements ConfigurationPoller,
        PollingRateHinter,
        AgentTaskScheduler.Target<ConfigurationPoller> {
  private static final Logger log = LoggerFactory.getLogger(ConfigurationPoller.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;

  private final String keyId;
  private final Ed25519PublicKey key;
  private final Config config;
  private final String tracerVersion;
  private final String containerId;
  private final String entityId;
  private final OkHttpClient httpClient;
  private final RatelimitedLogger ratelimitedLogger;
  private final Supplier<String> urlSupplier;
  private final PollerScheduler scheduler;
  private final long maxPayloadSize;
  private final boolean integrityChecks;

  private final Map<Product, ProductState> productStates = new EnumMap<>(Product.class);
  private final Map<File, ConfigurationChangesListener> fileListeners = new HashMap<>();
  private final List<ConfigurationEndListener> configurationEndListeners = new ArrayList<>();

  private final ClientState nextClientState = new ClientState();
  private final AtomicInteger startCount = new AtomicInteger(0);
  private long capabilities;
  private Duration durationHint;
  private PollerRequestFactory requestFactory;
  private RemoteConfigResponse.Factory responseFactory;
  private boolean fatalOnInitialization = false;

  public DefaultConfigurationPoller(
      Config config,
      String tracerVersion,
      String containerId,
      String entityId,
      Supplier<String> urlSupplier,
      OkHttpClient client) {
    this(
        config,
        tracerVersion,
        containerId,
        entityId,
        urlSupplier,
        client,
        new AgentTaskScheduler(AgentThreadFactory.AgentThread.REMOTE_CONFIG));
  }

  // for testing
  public DefaultConfigurationPoller(
      Config config,
      String tracerVersion,
      String containerId,
      String entityId,
      Supplier<String> urlSupplier,
      OkHttpClient httpClient,
      AgentTaskScheduler taskScheduler) {
    this.config = config;
    this.tracerVersion = tracerVersion;
    this.containerId = containerId;
    this.entityId = entityId;
    this.urlSupplier = urlSupplier;
    this.keyId = config.getRemoteConfigTargetsKeyId();
    String keyStr = config.getRemoteConfigTargetsKey();
    try {
      this.key = Ed25519PublicKey.fromByteArray(HexUtils.fromHexString(keyStr));
    } catch (InvalidEncodingException e) {
      throw new IllegalArgumentException("Bad public key: " + keyStr, e);
    }

    this.scheduler = new PollerScheduler(config, this, taskScheduler);
    log.debug("Started remote config poller every {} ms", scheduler.getInitialPollInterval());
    this.ratelimitedLogger =
        new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES);
    this.maxPayloadSize = config.getRemoteConfigMaxPayloadSizeBytes();
    this.integrityChecks = config.isRemoteConfigIntegrityCheckEnabled();
    this.httpClient = httpClient;
  }

  @Override
  public synchronized void addListener(
      Product product, ConfigurationChangesListener configurationChangesListener) {
    this.addListener(product, new SimpleProductListener(configurationChangesListener));
  }

  @Override
  public synchronized void addListener(Product product, ProductListener listener) {
    ProductState productState =
        this.productStates.computeIfAbsent(product, p -> new ProductState(product));
    productState.addProductListener(listener);
  }

  @Override
  public synchronized <T> void addListener(
      Product product,
      ConfigurationDeserializer<T> deserializer,
      ConfigurationChangesTypedListener<T> listener) {
    this.addListener(product, new SimpleProductListener(useDeserializer(deserializer, listener)));
  }

  @Override
  public synchronized void addListener(
      Product product, String configKey, ProductListener listener) {
    ProductState productState =
        this.productStates.computeIfAbsent(product, p -> new ProductState(product));
    productState.addProductListener(configKey, listener);
  }

  @Override
  public synchronized <T> void addListener(
      Product product,
      String configKey,
      ConfigurationDeserializer<T> deserializer,
      ConfigurationChangesTypedListener<T> listener) {
    this.addListener(
        product, configKey, new SimpleProductListener(useDeserializer(deserializer, listener)));
  }

  @Override
  public synchronized void removeListeners(Product product) {
    this.productStates.remove(product);
  }

  public synchronized <T> void addFileListener(
      File file,
      ConfigurationDeserializer<T> deserializer,
      ConfigurationChangesTypedListener<T> listener) {
    this.fileListeners.put(file, useDeserializer(deserializer, listener));
  }

  @Override
  public synchronized void addConfigurationEndListener(ConfigurationEndListener listener) {
    this.configurationEndListeners.add(listener);
  }

  @Override
  public synchronized void removeConfigurationEndListener(ConfigurationEndListener listener) {
    this.configurationEndListeners.removeIf(l -> l == listener);
  }

  @Override
  public synchronized void addCapabilities(long flags) {
    capabilities |= flags;
  }

  @Override
  public synchronized void removeCapabilities(long flags) {
    capabilities &= ~flags;
  }

  @Override
  public ConfigurationPoller get() {
    return this;
  }

  @Override
  public void start() {
    int prevCount = this.startCount.getAndIncrement();
    if (prevCount == 0) {
      scheduler.start();
    }
  }

  @Override
  public void stop() {
    int newCount = this.startCount.decrementAndGet();
    if (newCount == 0) {
      scheduler.stop();
    }
  }

  synchronized void poll(ConfigurationPoller poller) {
    for (Map.Entry<File, ConfigurationChangesListener> e : this.fileListeners.entrySet()) {
      loadFromFile(e.getKey(), e.getValue());
    }

    if (!this.productStates.isEmpty()) {

      if (!initialize()) {
        // Do not log anything before initialization to avoid excessive verboseness when remote
        // config is disabled in the agent. The urlSupplier will log failed attempts whenever it
        // actually makes requests to feature discovery (at a higher polling interval).
        return;
      }

      try {
        sendRequest(this::handleAgentResponse);
      } catch (InterruptedIOException ignored) {
      } catch (IOException | RuntimeException ex) {
        ExceptionHelper.rateLimitedLogException(
            ratelimitedLogger,
            log,
            ex,
            "Failed to poll remote configuration from {}",
            requestFactory.url.toString());
      }
    }
  }

  /** Tries to initialize remote config, and returns true if it is ready to run. */
  private boolean initialize() {
    if (fatalOnInitialization) {
      return false;
    }

    if (requestFactory != null && responseFactory != null) {
      return true;
    }

    final String url = urlSupplier.get();
    if (url == null) {
      return false;
    }

    try {
      // Initialization of these is delayed until the remote config URL is available.
      // See #initialize().
      Moshi moshi =
          new Moshi.Builder()
              .add(Instant.class, new InstantJsonAdapter())
              .add(ByteString.class, new RawJsonAdapter())
              .build();
      this.responseFactory = new RemoteConfigResponse.Factory(moshi);
      this.requestFactory =
          new PollerRequestFactory(config, tracerVersion, containerId, entityId, url, moshi);
    } catch (Exception e) {
      // We can't recover from this, so we'll not try to initialize again.
      fatalOnInitialization = true;
      log.error("Remote configuration poller initialization failed", e);
    }
    return true;
  }

  private Response fetchConfiguration() throws IOException {
    Request request =
        this.requestFactory.newConfigurationRequest(
            getSubscribedProductNames(),
            this.nextClientState,
            getCachedTargetFiles(),
            capabilities);
    if (request == null) {
      throw new IOException("Endpoint has not been discovered yet");
    }
    log.debug("Sending Remote configuration request: {}", request);
    Call call = this.httpClient.newCall(request);
    return call.execute();
  }

  private Collection<String> getSubscribedProductNames() {
    return this.productStates.keySet().stream().map(Enum::name).collect(Collectors.toList());
  }

  List<RemoteConfigRequest.CachedTargetFile> getCachedTargetFiles() {
    List<RemoteConfigRequest.CachedTargetFile> cachedTargetFiles = new ArrayList<>();

    for (ProductState state : productStates.values()) {
      cachedTargetFiles.addAll(state.getCachedTargetFiles());
    }
    return cachedTargetFiles;
  }

  List<ConfigState> getConfigState() {
    List<ConfigState> configStates = new ArrayList<>();

    for (ProductState state : productStates.values()) {
      configStates.addAll(state.getConfigStates());
    }
    return configStates;
  }

  void sendRequest(Consumer<ResponseBody> responseBodyConsumer) throws IOException {
    try (Response response = fetchConfiguration()) {
      if (response.code() == 404) {
        log.debug("Remote configuration endpoint is disabled");
        return;
      }
      if (response.code() == 204) {
        log.debug("No configuration changes (HTTP 204 No Content)");
        return;
      }
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

    try {
      verifyTargetsSignature(fleetResponse);
      verifyTargetsPresence(fleetResponse);
    } catch (RuntimeException rte) {
      ratelimitedLogger.warn("Error doing initial verifications: {}", rte.getMessage(), rte);
      this.nextClientState.hasError = true;
      this.nextClientState.error = rte.getMessage();
      return;
    }

    List<ReportableException> errors = new ArrayList<>();

    // TODO(POC): Revert to Map<Product, List<ParsedConfigKey>> once DEBUG endpoint is removed
    // Changed to ConfigKey to support RemappedConfigKey wrapper for DEBUG->ASM_SCA remapping
    // Original: Map<Product, List<ParsedConfigKey>> parsedKeysByProduct = new HashMap<>();
    Map<Product, List<datadog.remoteconfig.state.ConfigKey>> parsedKeysByProduct = new HashMap<>();

    for (String configKey : fleetResponse.getClientConfigs()) {
      try {
        ParsedConfigKey parsedConfigKey = ParsedConfigKey.parse(configKey);
        Product product = parsedConfigKey.getProduct();
        // TODO(POC): Remove this variable once DEBUG endpoint is removed
        // This allows using RemappedConfigKey wrapper for DEBUG->ASM_SCA remapping
        // After removal, directly add parsedConfigKey to the map
        datadog.remoteconfig.state.ConfigKey configKeyToAdd = parsedConfigKey;
        // TODO for debugging
        if (product == Product.DEBUG
            && "DEBUG".equalsIgnoreCase(parsedConfigKey.getProductName())
            && parsedConfigKey.getConfigId().startsWith("SCA_")) {
          product = Product.DEBUG;
          configKeyToAdd = new RemappedConfigKey(parsedConfigKey, Product.DEBUG);
          log.debug(
              "POC: Detected SCA config from DEBUG endpoint, remapping to ASM_SCA: {}", configKey);
        }

        // POC/TEMPORARY: Detect SCA configs from debugging endpoint
        // Backend serves SCA configs via: GET
        // /api/unstable/remote-config/debugging/configs/SCA_{id}
        // These arrive as DEBUG product (which maps to _UNKNOWN since DEBUG is not in Product enum)
        // with "SCA_" prefix in config ID. Remap to ASM_SCA product so existing ASM_SCA listeners
        // receive them.
        // TODO: Remove this once backend supports proper product-specific routing
        if (product == Product._UNKNOWN
            && "DEBUG".equalsIgnoreCase(parsedConfigKey.getProductName())
            && parsedConfigKey.getConfigId().startsWith("SCA_")) {
          product = Product.DEBUG;
          configKeyToAdd = new RemappedConfigKey(parsedConfigKey, Product.DEBUG);
          log.debug(
              "POC: Detected SCA config from DEBUG endpoint, remapping to ASM_SCA: {}", configKey);
        }

        if (!(productStates.containsKey(product))) {
          throw new ReportableException(
              "Told to handle config key "
                  + configKey
                  + ", but the product "
                  + parsedConfigKey.getProductName()
                  + " is not being handled");
        }
        parsedKeysByProduct.computeIfAbsent(product, k -> new ArrayList<>()).add(configKeyToAdd);
      } catch (ReportableException e) {
        errors.add(e);
      }
    }

    boolean appliedAny = false;
    for (Map.Entry<Product, ProductState> entry : productStates.entrySet()) {
      Product product = entry.getKey();
      ProductState state = entry.getValue();
      // TODO(POC): Revert to List<ParsedConfigKey> once DEBUG endpoint is removed
      // Original: List<ParsedConfigKey> relevantKeys = ...
      List<datadog.remoteconfig.state.ConfigKey> relevantKeys =
          parsedKeysByProduct.getOrDefault(product, Collections.EMPTY_LIST);
      appliedAny = state.apply(fleetResponse, relevantKeys, this) || appliedAny;
      if (state.hasError()) {
        errors.addAll(state.getErrors());
      }
    }

    if (appliedAny) {
      for (ConfigurationEndListener listener : this.configurationEndListeners) {
        runConfigurationEndListener(listener, errors);
      }
    }

    updateNextState(fleetResponse, buildErrorMessage(errors));

    rescheduleBaseOnConfiguration(this.durationHint);
  }

  private void runConfigurationEndListener(
      ConfigurationEndListener listener, List<ReportableException> errors) {
    try {
      listener.onConfigurationEnd();
    } catch (ReportableException re) {
      errors.add(re);
    } catch (RuntimeException rte) {
      // XXX: we have no way to report this error back
      // This is because errors are scoped to a specific config key and this listener
      // is about combining configuration from different products
      ratelimitedLogger.warn(
          "Error running configuration listener {}: {}", listener, rte.getMessage(), rte);
    }
  }

  private String buildErrorMessage(List<ReportableException> errors) {
    if (errors.isEmpty()) {
      return null;
    }
    if (errors.size() == 1) {
      return errors.get(0).getMessage();
    }
    StringBuilder aggregateMessage = new StringBuilder();
    aggregateMessage.append(
        String.format("Failed to apply configuration due to %d errors:%n", errors.size()));
    for (int i = 0; i < errors.size(); i++) {
      aggregateMessage.append(String.format(" (%d) %s%n", i + 1, errors.get(i).getMessage()));
    }
    return aggregateMessage.toString();
  }

  /**
   * @param fleetResponse the response the agent sent
   * @param error a procedural error that occurred during the update; it does not include errors
   *     during registered deserializers and config listeners. If there is an error, the
   *     targets_version in client.state is not updated (but the rest is).
   */
  private void updateNextState(RemoteConfigResponse fleetResponse, String error) {
    RemoteConfigResponse.Targets.TargetsSigned targetsSigned = fleetResponse.getTargetsSigned();

    long newTargetsVersion = targetsSigned.version;
    this.nextClientState.setState(
        // if there was an error, we did not apply the configurations fully
        // the system tests expect here the targets version not to be updated
        error == null ? newTargetsVersion : this.nextClientState.targetsVersion,
        getConfigState(),
        error,
        targetsSigned.custom != null ? targetsSigned.custom.opaqueBackendState : null);
  }

  private void rescheduleBaseOnConfiguration(Duration hint) {
    if (hint == null) {
      return;
    }

    scheduler.reschedule(hint.toMillis());
  }

  private void loadFromFile(File file, ConfigurationChangesListener listener) {
    log.debug("Loading configuration from file {}", file);

    try (InputStream inputStream =
        new SizeCheckedInputStream(Files.newInputStream(file.toPath()), maxPayloadSize)) {
      byte[] buffer = new byte[4096];
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream(4096);
      int bytesRead;
      do {
        bytesRead = inputStream.read(buffer);
        if (bytesRead > -1) {
          outputStream.write(buffer, 0, bytesRead);
        }
      } while (bytesRead > -1);

      try {
        listener.accept(file.getAbsolutePath(), outputStream.toByteArray(), NOOP);
        log.debug("Loaded configuration from file {}", file);
      } catch (Exception ex) {
        ratelimitedLogger.warn(
            "Failed reading or applying configuration from {}: {}", file, ex.getMessage());
      }
    } catch (IOException | RuntimeException ex) {
      ExceptionHelper.rateLimitedLogException(
          ratelimitedLogger, log, ex, "Unable to load config file: {}.", file);
    }
  }

  @Override
  public void suggestPollingRate(Duration duration) {
    if (durationHint == null) {
      durationHint = duration;
    } else if (duration.compareTo(durationHint) < 0) {
      durationHint = duration;
    }
  }

  private void verifyTargetsSignature(RemoteConfigResponse resp) {
    if (!integrityChecks) {
      return;
    }

    Ed25519Signature sig;
    byte[] canonicalTargetsSigned;
    try {
      {
        String targetsSignatureStr = resp.getTargetsSignature(this.keyId);
        sig = Ed25519Signature.fromByteArray(HexUtils.fromHexString(targetsSignatureStr));
      }
      {
        Map<String, Object> untypedTargetsSigned = resp.getUntypedTargetsSigned();
        canonicalTargetsSigned = JsonCanonicalizer.canonicalize(untypedTargetsSigned);
      }
    } catch (RuntimeException rte) {
      throw new ReportableException(
          "Error reading signature or canonicalizing targets.signed: " + rte.getMessage(), rte);
    }
    boolean valid = this.key.verify(canonicalTargetsSigned, sig);
    if (!valid) {
      throw new ReportableException(
          "Signature verification failed for targets.signed. Key id: " + this.keyId);
    }
  }

  private void verifyTargetsPresence(RemoteConfigResponse resp) {
    if (resp.targetFiles == null) {
      return;
    }
    for (RemoteConfigResponse.TargetFile file : resp.targetFiles) {
      RemoteConfigResponse.Targets.ConfigTarget target = resp.getTarget(file.path);
      if (target == null) {
        throw new ReportableException(
            "Path " + file.path + " is in target_files, but not in targets.signed");
      }
    }
  }

  /**
   * POC/TEMPORARY: Wrapper for ParsedConfigKey that overrides the product.
   *
   * <p>This is used to remap DEBUG product configs with SCA_ prefix to ASM_SCA product. The wrapper
   * delegates all calls to the original ParsedConfigKey except getProduct() which returns the
   * remapped product.
   *
   * <p><b>TODO(POC): DELETE THIS ENTIRE CLASS once DEBUG endpoint is removed.</b>
   *
   * <p>This class was created specifically for the POC where SCA configs arrive via the DEBUG
   * product endpoint. Once the backend sends SCA configs directly with the correct product
   * (ASM_SCA), this wrapper class and all ConfigKey generalization in ProductState should be
   * removed.
   *
   * <p>When removing this class:
   *
   * <ul>
   *   <li>Delete the entire RemappedConfigKey class
   *   <li>Remove the DEBUG product detection logic in handleAgentResponse()
   *   <li>Revert all ConfigKey types back to ParsedConfigKey in ProductState.java
   *   <li>Revert the parsedKeysByProduct map type back to Map&lt;Product,
   *       List&lt;ParsedConfigKey&gt;&gt;
   * </ul>
   */
  private static class RemappedConfigKey implements datadog.remoteconfig.state.ConfigKey {
    private final ParsedConfigKey delegate;
    private final Product remappedProduct;

    RemappedConfigKey(ParsedConfigKey delegate, Product remappedProduct) {
      this.delegate = delegate;
      this.remappedProduct = remappedProduct;
    }

    @Override
    public Product getProduct() {
      return remappedProduct;
    }

    @Override
    public String getProductName() {
      return delegate.getProductName();
    }

    @Override
    public String getOrg() {
      return delegate.getOrg();
    }

    @Override
    public Integer getVersion() {
      return delegate.getVersion();
    }

    @Override
    public String getConfigId() {
      return delegate.getConfigId();
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
  }
}
