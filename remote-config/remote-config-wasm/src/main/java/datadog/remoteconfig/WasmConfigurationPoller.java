package datadog.remoteconfig;

import static datadog.remoteconfig.PollingHinterNoop.NOOP;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.datadoghq.remoteconfig.poc.HttpTransport;
import com.datadoghq.remoteconfig.poc.RemoteConfigClient;
import com.datadoghq.remoteconfig.poc.RemoteConfigHost;
import datadog.logging.RatelimitedLogger;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.remoteconfig.state.SimpleProductListener;
import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.RandomUtils;
import datadog.trace.util.TagsHelper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ConfigurationPoller} backed by the shared libdatadog WASM remote-config reactor (agent
 * mode): the guest owns TUF state, request building and change detection, while this host supplies
 * HTTP through the tracer's OkHttp client and dispatches raw config bytes to the existing {@link
 * ProductListener} model.
 *
 * <p>The reactor artifact (AOT-compiled machine classes plus the stripped module) ships with this
 * module as a published dependency; see the module README for building it from the libdatadog
 * submodule instead.
 */
public class WasmConfigurationPoller
    implements ConfigurationPoller, AgentTaskScheduler.Target<ConfigurationPoller> {
  private static final Logger log = LoggerFactory.getLogger(WasmConfigurationPoller.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;

  /**
   * libdd {@code RemoteConfigCapabilities} names by bit position; the ordinal matches the spec bit
   * reserved in {@link Capabilities}.
   */
  private static final String[] UPSTREAM_CAPABILITIES = {
    null, // bit 0 unused
    "ASM_ACTIVATION",
    "ASM_IP_BLOCKING",
    "ASM_DD_RULES",
    "ASM_EXCLUSIONS",
    "ASM_REQUEST_BLOCKING",
    "ASM_RESPONSE_BLOCKING",
    "ASM_USER_BLOCKING",
    "ASM_CUSTOM_RULES",
    "ASM_CUSTOM_BLOCKING_RESPONSE",
    "ASM_TRUSTED_IPS",
    "ASM_API_SECURITY_SAMPLE_RATE",
    "APM_TRACING_SAMPLE_RATE",
    "APM_TRACING_LOGS_INJECTION",
    "APM_TRACING_HTTP_HEADER_TAGS",
    "APM_TRACING_CUSTOM_TAGS",
    "ASM_PROCESSOR_OVERRIDES",
    "ASM_CUSTOM_DATA_SCANNERS",
    "ASM_EXCLUSION_DATA",
    "APM_TRACING_ENABLED",
    "APM_TRACING_DATA_STREAMS_ENABLED",
    "ASM_RASP_SQLI",
    "ASM_RASP_LFI",
    "ASM_RASP_SSRF",
    "ASM_RASP_SHI",
    "ASM_RASP_XXE",
    "ASM_RASP_RCE",
    "ASM_RASP_NOSQLI",
    "ASM_RASP_XSS",
    "APM_TRACING_SAMPLE_RULES",
    "CSM_ACTIVATION",
    "ASM_AUTO_USER_INSTRUM_MODE",
    "ASM_ENDPOINT_FINGERPRINT",
    "ASM_SESSION_FINGERPRINT",
    "ASM_NETWORK_FINGERPRINT",
    "ASM_HEADER_FINGERPRINT",
    "ASM_TRUNCATION_RULES",
    "ASM_RASP_CMDI",
    "APM_TRACING_ENABLE_DYNAMIC_INSTRUMENTATION",
    "APM_TRACING_ENABLE_EXCEPTION_REPLAY",
    "APM_TRACING_ENABLE_CODE_ORIGIN",
    "APM_TRACING_ENABLE_LIVE_DEBUGGING",
    "ASM_DD_MULTICONFIG",
    "ASM_TRACE_TAGGING_RULES",
    "ASM_EXTENDED_DATA_COLLECTION",
    "APM_TRACING_MULTICONFIG",
    "FFE_FLAG_CONFIGURATION_RULES",
    "DD_DATA_STREAMS_TRANSACTION_EXTRACTORS",
    "LLM_OBS_ACTIVATION",
    null, // bit 49 not assigned upstream
    "FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1",
  };

  private final Config config;
  private final String tracerVersion;
  private final String containerId;
  private final String entityId;
  private final Supplier<String> urlSupplier;
  private final OkHttpClientProvider clientProvider;
  private final RatelimitedLogger ratelimitedLogger;
  private final long initialPollInterval;
  private final AgentTaskScheduler taskScheduler;
  private volatile AgentTaskScheduler.Scheduled<ConfigurationPoller> scheduled;

  private final Map<Product, ProductListeners> productListeners = new EnumMap<>(Product.class);
  private final List<ConfigurationEndListener> configurationEndListeners = new ArrayList<>();

  private final AtomicInteger startCount = new AtomicInteger(0);
  private long capabilities;
  private String clientId;

  // lazily initialized on first poll, once the config URL is available
  private RemoteConfigHost host;
  private RemoteConfigClient client;
  private HttpTransport transport;
  private String clientUrl;
  private Set<Product> clientProducts;
  private long clientCapabilities;
  private boolean fatalOnInitialization = false;

  public WasmConfigurationPoller(
      Config config,
      String tracerVersion,
      String containerId,
      String entityId,
      Supplier<String> urlSupplier,
      okhttp3.OkHttpClient httpClient) {
    this(
        config,
        tracerVersion,
        containerId,
        entityId,
        urlSupplier,
        () -> httpClient,
        new AgentTaskScheduler(AgentThreadFactory.AgentThread.REMOTE_CONFIG));
  }

  /** For testing. */
  public WasmConfigurationPoller(
      Config config,
      String tracerVersion,
      String containerId,
      String entityId,
      Supplier<String> urlSupplier,
      OkHttpClientProvider clientProvider,
      AgentTaskScheduler taskScheduler) {
    this.config = config;
    this.tracerVersion = tracerVersion.replace('~', '+');
    this.containerId = containerId;
    this.entityId = entityId;
    this.urlSupplier = urlSupplier;
    this.clientProvider = clientProvider;
    this.initialPollInterval = (long) (config.getRemoteConfigPollIntervalSeconds() * 1000);
    this.taskScheduler = taskScheduler;
    this.ratelimitedLogger =
        new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES);
  }

  /** Supplies the shared OkHttp client lazily so the poller can be built before it is. */
  public interface OkHttpClientProvider {
    okhttp3.OkHttpClient client();
  }

  @Override
  public synchronized void addListener(Product product, ProductListener listener) {
    productListeners
        .computeIfAbsent(product, p -> new ProductListeners())
        .productListeners
        .add(listener);
  }

  @Override
  @SuppressWarnings("unchecked")
  public synchronized <T> void addListener(
      Product product,
      ConfigurationDeserializer<T> deserializer,
      ConfigurationChangesTypedListener<T> listener) {
    addListener(
        product,
        new SimpleProductListener(
            ConfigurationChangesTypedListener.Builder.useDeserializer(deserializer, listener)));
  }

  @Override
  public synchronized void addListener(
      Product product, String configKey, ProductListener listener) {
    productListeners
        .computeIfAbsent(product, p -> new ProductListeners())
        .configListeners
        .put(configKey, listener);
  }

  @Override
  @SuppressWarnings("unchecked")
  public synchronized <T> void addListener(
      Product product,
      String configKey,
      ConfigurationDeserializer<T> deserializer,
      ConfigurationChangesTypedListener<T> listener) {
    addListener(
        product,
        configKey,
        new SimpleProductListener(
            ConfigurationChangesTypedListener.Builder.useDeserializer(deserializer, listener)));
  }

  @Override
  public synchronized void addListener(Product product, ConfigurationChangesListener listener) {
    addListener(product, new SimpleProductListener(listener));
  }

  @Override
  public synchronized void removeListeners(Product product) {
    productListeners.remove(product);
  }

  @Override
  public synchronized void addConfigurationEndListener(ConfigurationEndListener listener) {
    configurationEndListeners.add(listener);
  }

  @Override
  public synchronized void removeConfigurationEndListener(ConfigurationEndListener listener) {
    configurationEndListeners.removeIf(l -> l == listener);
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
    int prevCount = startCount.getAndIncrement();
    if (prevCount == 0) {
      reschedule(0);
    }
  }

  @Override
  public void stop() {
    int newCount = startCount.decrementAndGet();
    if (newCount == 0) {
      AgentTaskScheduler.Scheduled<ConfigurationPoller> localScheduled = this.scheduled;
      if (localScheduled != null) {
        localScheduled.cancel();
      }
    }
  }

  private void reschedule(long initialDelay) {
    AgentTaskScheduler.Scheduled<ConfigurationPoller> localScheduled = this.scheduled;
    if (localScheduled != null) {
      localScheduled.cancel();
    }
    this.scheduled =
        taskScheduler.scheduleAtFixedRate(
            WasmConfigurationPoller::poll, this, initialDelay, initialPollInterval, MILLISECONDS);
  }

  private static void poll(ConfigurationPoller poller) {
    ((WasmConfigurationPoller) poller).doPoll();
  }

  void doPoll() {
    if (productListeners.isEmpty()) {
      return;
    }
    try {
      if (!ensureClient()) {
        return;
      }
      JSONObject outcome = client.pollRaw(transport);
      if (outcome.has("error")) {
        ratelimitedLogger.warn("Remote configuration poll failed: {}", outcome.getString("error"));
        return;
      }
      dispatch(outcome);
    } catch (RuntimeException ex) {
      ratelimitedLogger.warn("Failed to poll remote configuration through the WASM client", ex);
    }
  }

  /** Loads the reactor and creates the guest client once the config URL is available. */
  private boolean ensureClient() {
    if (fatalOnInitialization) {
      return false;
    }
    if (host == null) {
      try {
        host = RemoteConfigHost.load();
        transport = new OkHttpTransport(clientProvider.client());
        clientId = RandomUtils.randomUUID().toString();
      } catch (RuntimeException error) {
        fatalOnInitialization = true;
        log.error("Remote configuration WASM client initialization failed", error);
        return false;
      }
    }
    String url = urlSupplier.get();
    if (url == null) {
      return false;
    }
    Set<Product> products = new LinkedHashSet<>(productListeners.keySet());
    long capabilities = capabilitiesSnapshot();
    if (client != null
        && url.equals(clientUrl)
        && products.equals(clientProducts)
        && capabilities == clientCapabilities) {
      return true;
    }
    if (client != null) {
      try {
        client.close();
      } catch (RuntimeException ignored) {
      }
    }
    JSONObject options =
        new JSONObject()
            .put("clientId", clientId)
            .put("runtimeId", runtimeId())
            .put("service", TagsHelper.sanitize(config.getServiceName()))
            .put("env", TagsHelper.sanitize(config.getEnv()))
            .put("appVersion", TagsHelper.sanitize(config.getVersion()))
            .put("language", "java")
            .put("tracerVersion", tracerVersion)
            .put("url", baseUrl(url))
            .put("timeoutMs", 30_000)
            .put("tags", tags())
            .put("products", upstreamProductNames(products))
            .put("capabilities", capabilityNames(capabilities));
    client = RemoteConfigClient.create(host.abi(), options);
    clientUrl = url;
    clientProducts = products;
    clientCapabilities = capabilities;
    return true;
  }

  /** Dispatches raw change records to listeners, then acknowledges every change to the guest. */
  private void dispatch(JSONObject outcome) {
    JSONArray changes = outcome.optJSONArray("changes");
    if (changes == null || changes.isEmpty()) {
      return;
    }
    Map<Product, List<JSONObject>> changesByProduct = new EnumMap<>(Product.class);
    List<JSONObject> unclaimed = null;
    for (Object entry : changes) {
      JSONObject change = (JSONObject) entry;
      Product product = parseProduct(change.getString("path"));
      if (product != null) {
        changesByProduct.computeIfAbsent(product, p -> new ArrayList<>()).add(change);
      } else {
        (unclaimed == null ? unclaimed = new ArrayList<>() : unclaimed).add(change);
      }
    }
    boolean anyDispatched = false;
    // products are processed in enum order, like DefaultConfigurationPoller
    for (Product product : Product.values()) {
      List<JSONObject> productChanges = changesByProduct.get(product);
      if (productChanges != null) {
        anyDispatched |= dispatchProductChanges(product, productChanges);
      }
    }
    // acknowledge unsubscribed changes as errored so the guest does not retry them
    if (unclaimed != null) {
      for (JSONObject change : unclaimed) {
        ack(change.getString("path"), 3, "no listener subscribed for product");
      }
    }
    if (anyDispatched) {
      List<ConfigurationEndListener> endListeners;
      synchronized (this) {
        endListeners = new ArrayList<>(configurationEndListeners);
      }
      for (ConfigurationEndListener listener : endListeners) {
        try {
          listener.onConfigurationEnd();
        } catch (RuntimeException error) {
          log.warn("Error in remote configuration end listener", error);
        }
      }
    }
  }

  private boolean dispatchProductChanges(Product product, List<JSONObject> changes) {
    ProductListeners listeners = productListeners.get(product);
    if (listeners == null) {
      for (JSONObject change : changes) {
        ack(change.getString("path"), 3, "no listener subscribed for product");
      }
      return false;
    }
    List<JSONObject> applies = new ArrayList<>(changes.size());
    List<JSONObject> removes = new ArrayList<>(changes.size());
    for (JSONObject change : changes) {
      if ("remove".equals(change.getString("kind"))) {
        removes.add(change);
      } else {
        applies.add(change);
      }
    }
    // ASM_DD needs removes before applies to avoid duplicate ddwaf rules and memory spikes
    boolean dispatchRemovesFirst = product == Product.ASM_DD;
    if (dispatchRemovesFirst) {
      dispatchRemoves(product, listeners, removes);
      dispatchApplies(product, listeners, applies);
    } else {
      dispatchApplies(product, listeners, applies);
      dispatchRemoves(product, listeners, removes);
    }
    if (!changes.isEmpty()) {
      for (ProductListener listener : listeners.all()) {
        try {
          listener.commit(NOOP);
        } catch (RuntimeException error) {
          log.warn("Error committing remote configuration product {}", product, error);
        }
      }
      return true;
    }
    return false;
  }

  private void dispatchApplies(
      Product product, ProductListeners listeners, List<JSONObject> changes) {
    for (JSONObject change : changes) {
      String path = change.getString("path");
      byte[] content;
      try {
        Object contents = change.get("contents");
        if (contents == JSONObject.NULL) {
          throw new IllegalArgumentException("add/update without contents");
        }
        content = host.abi().read((int) contents);
      } catch (RuntimeException error) {
        log.warn("Error reading remote configuration contents for {}", path, error);
        ack(path, 3, error.getMessage());
        continue;
      }
      ParsedConfigKey configKey;
      try {
        configKey = ParsedConfigKey.parse(path);
      } catch (RuntimeException error) {
        ack(path, 3, error.getMessage());
        continue;
      }
      String applyError = null;
      for (ProductListener listener : listeners.forConfigId(configKey.getConfigId())) {
        try {
          listener.accept(configKey, content, NOOP);
        } catch (Exception error) {
          log.warn("Error applying remote configuration {}", path, error);
          applyError = error.getMessage();
        }
      }
      if (applyError == null) {
        ack(path, 2, null);
      } else {
        ack(path, 3, applyError);
      }
    }
  }

  private void dispatchRemoves(
      Product product, ProductListeners listeners, List<JSONObject> changes) {
    for (JSONObject change : changes) {
      String path = change.getString("path");
      ParsedConfigKey configKey;
      try {
        configKey = ParsedConfigKey.parse(path);
      } catch (RuntimeException error) {
        ack(path, 3, error.getMessage());
        continue;
      }
      for (ProductListener listener : listeners.forConfigId(configKey.getConfigId())) {
        try {
          listener.remove(configKey, NOOP);
        } catch (Exception error) {
          log.warn("Error removing remote configuration {}", path, error);
        }
      }
      ack(path, 2, null);
    }
  }

  private void ack(String path, int state, String error) {
    JSONObject acknowledgement = new JSONObject().put("path", path).put("state", state);
    if (error != null) {
      acknowledgement.put("error", error);
    }
    client.ack(acknowledgement);
  }

  private Product parseProduct(String path) {
    try {
      return ParsedConfigKey.parse(path).getProduct();
    } catch (RuntimeException error) {
      return null;
    }
  }

  /** Reads the capability bitmask under the same lock as {@code addCapabilities}. */
  private synchronized long capabilitiesSnapshot() {
    return capabilities;
  }

  private String runtimeId() {
    String runtimeId = config.getRuntimeId();
    if (runtimeId == null || runtimeId.isEmpty()) {
      runtimeId = RandomUtils.randomUUID().toString();
    }
    return runtimeId;
  }

  private List<String> tags() {
    List<String> tags = new ArrayList<>();
    for (Map.Entry<String, String> entry : config.getGlobalTags().entrySet()) {
      tags.add(entry.getKey() + ":" + entry.getValue());
    }
    String env = config.getEnv();
    if (env != null && !env.isEmpty()) {
      tags.add("env:" + env);
    }
    String version = config.getVersion();
    if (version != null && !version.isEmpty()) {
      tags.add("version:" + version);
    }
    tags.add("tracer_version:" + tracerVersion);
    return tags;
  }

  /** The reactor appends {@code /v0.7/config}; the agent root URL is what it expects. */
  private static String baseUrl(String url) {
    int index = url.indexOf("/v0.7/config");
    return index >= 0 ? url.substring(0, index) : url;
  }

  private static List<String> upstreamProductNames(Collection<Product> products) {
    List<String> names = new ArrayList<>(products.size());
    for (Product product : products) {
      if (product == Product._UNKNOWN) {
        throw new IllegalArgumentException("Unsupported remote config product: " + product);
      }
      // dd SCREAMING_SNAKE_CASE enum names match the guest's strum serialization directly
      names.add(product.name());
    }
    return names;
  }

  private static List<String> capabilityNames(long capabilities) {
    List<String> names = new ArrayList<>();
    // array index = spec bit = upstream ordinal; bit 0 is unused
    for (int bit = 1; bit < UPSTREAM_CAPABILITIES.length && bit < 63; bit++) {
      if ((capabilities & (1L << bit)) != 0) {
        String name = UPSTREAM_CAPABILITIES[bit];
        if (name != null) {
          names.add(name);
        } else {
          log.debug("Skipping remote config capability bit {} with no upstream name", bit);
        }
      }
    }
    return names;
  }

  /** The listeners subscribed for one product: all-product listeners plus per-config-key ones. */
  private static final class ProductListeners {
    final List<ProductListener> productListeners = new ArrayList<>();
    final Map<String, ProductListener> configListeners = new HashMap<>();

    List<ProductListener> forConfigId(String configId) {
      List<ProductListener> result = new ArrayList<>(productListeners);
      ProductListener configListener = configListeners.get(configId);
      if (configListener != null) {
        result.add(configListener);
      }
      return result;
    }

    List<ProductListener> all() {
      List<ProductListener> result = new ArrayList<>(productListeners);
      result.addAll(configListeners.values());
      return result;
    }
  }
}
