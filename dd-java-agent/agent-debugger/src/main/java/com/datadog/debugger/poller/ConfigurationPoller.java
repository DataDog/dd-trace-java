package com.datadog.debugger.poller;

import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.tuf.IntegrityCheckException;
import com.datadog.debugger.tuf.RemoteConfigResponse;
import com.datadog.debugger.util.ExceptionHelper;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.TagsHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import datadog.trace.relocate.api.RatelimitedLogger;
import datadog.trace.util.AgentTaskScheduler;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles polling debugger configuration from datadog agent/Remote Configuration */
public class ConfigurationPoller implements AgentTaskScheduler.Target<ConfigurationPoller> {
  private static final Logger log = LoggerFactory.getLogger(ConfigurationPoller.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;
  private final PollerHttpClient httpClient;
  private final RatelimitedLogger ratelimitedLogger;
  private final String serviceName;
  private final ConfigurationChangesListener listener;
  private final PollerScheduler scheduler;
  private final Moshi moshi;
  private final long maxPayloadSize;
  private Path probeFilePath;
  private boolean isFileMode = false;

  public interface ConfigurationChangesListener {
    boolean accept(Configuration configuration);
  }

  public ConfigurationPoller(
      Config config, ConfigurationChangesListener listener, String configEndpoint) {
    this(
        config,
        listener,
        new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES),
        configEndpoint,
        AgentTaskScheduler.INSTANCE);
  }

  ConfigurationPoller(
      Config config,
      ConfigurationChangesListener listener,
      RatelimitedLogger ratelimitedLogger,
      String configEndpoint,
      AgentTaskScheduler taskScheduler) {
    String probePrefixUrl = config.getFinalDebuggerProbeUrl();
    if (probePrefixUrl == null || probePrefixUrl.length() == 0) {
      throw new IllegalArgumentException("Probe url is empty");
    }
    serviceName = TagsHelper.sanitize(config.getServiceName());
    String appProbesUrl = probePrefixUrl + "/" + configEndpoint;
    String probeFileLocation = config.getDebuggerProbeFileLocation();
    if (probeFileLocation != null) {
      probeFilePath = Paths.get(probeFileLocation);
      isFileMode = true;
    }
    this.scheduler = new PollerScheduler(config, this, taskScheduler);
    log.debug(
        "Started Probes Poller every {}ms with target url {}",
        scheduler.getInitialPollInterval(),
        appProbesUrl);
    this.listener = listener;
    this.ratelimitedLogger = ratelimitedLogger;
    this.maxPayloadSize = config.getDebuggerMaxPayloadSize();

    moshi = MoshiHelper.createMoshiConfig();

    Request request = PollerRequestFactory.newConfigurationRequest(config, appProbesUrl, moshi);
    // Use same timeout everywhere for simplicity
    // FIXME specific config for poll
    Duration requestTimeout = Duration.ofSeconds(config.getDebuggerUploadTimeout());
    this.httpClient = new PollerHttpClient(request, requestTimeout);
  }

  // visible for testing
  ConfigurationPoller(Config config, ConfigurationChangesListener listener) {
    this(
        config,
        listener,
        new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES),
        "",
        AgentTaskScheduler.INSTANCE);
  }

  ConfigurationPoller(
      Config config, ConfigurationChangesListener listener, AgentTaskScheduler taskScheduler) {
    this(
        config,
        listener,
        new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES),
        "",
        taskScheduler);
  }

  @Override
  public ConfigurationPoller get() {
    return this;
  }

  public void start() {
    scheduler.start();
  }

  public void stop() {
    scheduler.stop();
    httpClient.stop();
  }

  void pollDebuggerProbes(AgentTaskScheduler.Target<ConfigurationPoller> target) {
    if (isFileMode) {
      loadFromFile();
    } else {
      try {
        sendRequest(this::handleAgentResponse);
      } catch (IOException ex) {
        ExceptionHelper.rateLimitedLogException(
            ratelimitedLogger,
            log,
            ex,
            "Failed to poll probes from {}",
            httpClient.getRequest().url().toString());
      }
    }
  }

  void sendRequest(Consumer<ResponseBody> responseBodyConsumer) throws IOException {
    try (Response response = httpClient.fetchConfiguration()) {
      ResponseBody body = response.body();
      if (response.isSuccessful()) {
        if (body == null) {
          ratelimitedLogger.warn("No body content while polling probes");
          return;
        }
        responseBodyConsumer.accept(body);
        return;
      }
      // Retrieve body content for detailed error messages
      if (body != null && MediaType.get("application/json").equals(body.contentType())) {
        try {
          ratelimitedLogger.warn(
              "Failed to poll probes: unexpected response code {} {} {}",
              response.message(),
              response.code(),
              body.string());
        } catch (IOException ex) {
          ExceptionHelper.rateLimitedLogException(
              ratelimitedLogger, log, ex, "Error while getting error message body");
        }
      } else {
        ratelimitedLogger.warn(
            "Failed to poll probes: unexpected response code {} {}",
            response.message(),
            response.code());
      }
    }
  }

  public PollerScheduler getScheduler() {
    return scheduler;
  }

  private void handleAgentResponse(ResponseBody body) {
    try (InputStream inputStream = new SizeCheckedInputStream(body.byteStream(), maxPayloadSize)) {
      RemoteConfigResponse fleetResponse = new RemoteConfigResponse(inputStream, moshi);
      String remoteConfigPath = getRemoteConfigPath();
      Optional<byte[]> maybeFileContent = fleetResponse.getFileContents(remoteConfigPath);
      if (!maybeFileContent.isPresent()) {
        ratelimitedLogger.warn("No content for " + remoteConfigPath);
        return;
      }
      byte[] fileContent = maybeFileContent.get();
      Configuration config = deserializeDebuggerConfiguration(fileContent);
      applyConfiguration(config);
    } catch (IOException | IntegrityCheckException e) {
      throw new RuntimeException("Error validating signed configuration", e);
    }
  }

  private void applyConfiguration(Configuration config) {
    if (!listener.accept(config)) {
      return;
    }
    rescheduleBaseOnConfiguration(config);
  }

  private void rescheduleBaseOnConfiguration(Configuration config) {
    if (config != null) {

      // try to use server suggested interval
      Configuration.OpsConfiguration opsConfiguration = config.getOpsConfig();
      if (opsConfiguration != null) {
        log.debug(
            "Using server suggested polling interval of {}ms",
            opsConfiguration.getPollIntervalDuration().toMillis());
        scheduler.reschedule(opsConfiguration.getPollIntervalDuration().toMillis());
        return;
      }

      // if we have probes, keep polling at initial rate
      if (!config.getDefinitions().isEmpty()) {
        return;
      }
    }
  }

  private Configuration deserializeDebuggerConfiguration(byte[] configBytes) throws IOException {
    Configuration config;
    try {
      String configStr = new String(configBytes);
      JsonAdapter<Configuration> adapter = moshi.adapter(Configuration.class);
      config = adapter.fromJson(configStr);
    } catch (IOException ex) {
      ExceptionHelper.rateLimitedLogException(
          ratelimitedLogger,
          log,
          ex,
          "Failed to deserialize configuration {}",
          new String(configBytes, StandardCharsets.UTF_8));
      return null;
    }
    if (config.getId().equals(serviceName)) {
      return config;
    } else {
      log.warn("configuration id mismatch, expected {} but got {}", serviceName, config.getId());
    }
    return null;
  }

  private void loadFromFile() {
    log.debug("try to load from file...");
    try (InputStream inputStream =
        new SizeCheckedInputStream(
            new FileInputStream(this.probeFilePath.toFile()), maxPayloadSize)) {
      byte[] buffer = new byte[4096];
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream(4096);
      int bytesRead;
      do {
        bytesRead = inputStream.read(buffer);
        if (bytesRead > -1) {
          outputStream.write(buffer, 0, bytesRead);
        }
      } while (bytesRead > -1);
      Configuration configuration = deserializeDebuggerConfiguration(outputStream.toByteArray());
      log.debug("Probe definitions loaded from file {}", this.probeFilePath);
      listener.accept(configuration);
    } catch (IOException ex) {
      ExceptionHelper.rateLimitedLogException(
          ratelimitedLogger, log, ex, "Unable to load config file: {}.", this.probeFilePath);
    }
  }

  private String getRemoteConfigPath() {
    return "datadog/2/LIVE_DEBUGGING/"
        + UUID.nameUUIDFromBytes(serviceName.getBytes(StandardCharsets.UTF_8))
        + "/config";
  }
}
