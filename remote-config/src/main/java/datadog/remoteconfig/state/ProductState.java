package datadog.remoteconfig.state;

import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.tuf.MissingContentException;
import datadog.remoteconfig.tuf.RemoteConfigRequest;
import datadog.remoteconfig.tuf.RemoteConfigResponse;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProductState {
  private static final Logger log = LoggerFactory.getLogger(ProductState.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;
  private final RatelimitedLogger ratelimitedLogger;

  final Product product;
  private final Map<String /*cfg key*/, RemoteConfigRequest.CachedTargetFile> cachedTargetFiles =
      new HashMap<>();
  private final Map<String /*cfg key*/, RemoteConfigRequest.ClientInfo.ClientState.ConfigState>
      configStates = new HashMap<>();

  ConfigurationPoller.ReportableException error;

  public ProductState(Product product) {
    this.product = product;

    this.ratelimitedLogger =
        new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES);
  }

  public void apply(
      RemoteConfigResponse fleetResponse,
      List<ParsedConfigKey> relevantKeys,
      ConfigurationChangesListener.PollingRateHinter hinter,
      ProductListener listener) {
    error = null;

    List<String> configBeenUsedByProduct = new ArrayList<>();
    boolean changesDetected = false;

    for (ParsedConfigKey parsedConfigKey : relevantKeys) {
      try {
        String configKey = parsedConfigKey.toString();
        RemoteConfigResponse.Targets.ConfigTarget target =
            getTargetOrThrow(fleetResponse, configKey);
        configBeenUsedByProduct.add(configKey);

        if (isTargetChanged(configKey, target)) {
          changesDetected = true;
          byte[] content = getTargetFileContent(fleetResponse, configKey);
          callListenerApplyTarget(fleetResponse, hinter, listener, parsedConfigKey, content);
        }

      } catch (ConfigurationPoller.ReportableException e) {
        this.error = e;
      }
    }

    for (String configKey : cachedTargetFiles.keySet()) {
      if (!configBeenUsedByProduct.contains(configKey)) {
        changesDetected = true;
        callListenerRemoveTarget(hinter, listener, ParsedConfigKey.parse(configKey));
      }
    }

    if (changesDetected) {
      callListenerCommit(hinter, listener);
    }
  }

  private void callListenerApplyTarget(
      RemoteConfigResponse fleetResponse,
      ConfigurationChangesListener.PollingRateHinter hinter,
      ProductListener listener,
      ParsedConfigKey configKey,
      byte[] content) {

    try {
      listener.accept(configKey, content, hinter);
      updateConfigState(fleetResponse, configKey.toString(), null);
    } catch (ConfigurationPoller.ReportableException e) {
      error = e;
    } catch (Exception ex) {
      updateConfigState(fleetResponse, configKey.toString(), ex);
      ratelimitedLogger.warn("Error processing config key {}: {}", configKey, ex.getMessage(), ex);
    }
  }

  private void callListenerRemoveTarget(
      ConfigurationChangesListener.PollingRateHinter hinter,
      ProductListener listener,
      ParsedConfigKey configKey) {
    try {
      listener.remove(configKey, hinter);
    } catch (Exception ex) {
      ratelimitedLogger.warn("Error handling configuration removal for " + configKey, ex);
    }
    cachedTargetFiles.remove(configKey.toString());
    configStates.remove(configKey.toString());
  }

  private void callListenerCommit(
      ConfigurationChangesListener.PollingRateHinter hinter, ProductListener listener) {
    try {
      listener.commit(hinter);
    } catch (ConfigurationPoller.ReportableException e) {
      error = e;
    } catch (Exception ex) {
      ratelimitedLogger.warn(
          "Error committing changes for product {}: {}", product, ex.getMessage(), ex);
    }
  }

  RemoteConfigResponse.Targets.ConfigTarget getTargetOrThrow(
      RemoteConfigResponse fleetResponse, String configKey) {
    RemoteConfigResponse.Targets.ConfigTarget target = fleetResponse.getTarget(configKey);
    if (target == null) {
      throw new ConfigurationPoller.ReportableException(
          "Told to apply config for "
              + configKey
              + " but no corresponding entry exists in targets.targets_signed.targets");
    }
    return target;
  }

  boolean isTargetChanged(String configKey, RemoteConfigResponse.Targets.ConfigTarget target) {
    RemoteConfigRequest.CachedTargetFile cachedTargetFile = cachedTargetFiles.get(configKey);
    if (cachedTargetFile != null && cachedTargetFile.hashesMatch(target.hashes)) {
      log.debug("No change in configuration for key {}", configKey);
      return false;
    }
    return true;
  }

  byte[] getTargetFileContent(RemoteConfigResponse fleetResponse, String configKey) {
    // fetch the content
    byte[] maybeFileContent;
    try {
      maybeFileContent = fleetResponse.getFileContents(configKey);
    } catch (MissingContentException e) {
      if (cachedTargetFiles.containsKey(configKey)) {
        throw new ConfigurationPoller.ReportableException(
            "Told to apply config "
                + configKey
                + " but content not present even though hash differs from that of 'cached file'");
      }
      throw new ConfigurationPoller.ReportableException(e.getMessage());
    } catch (Exception e) {
      throw new ConfigurationPoller.ReportableException(e.getMessage());
    }

    return maybeFileContent;
  }

  private void updateConfigState(
      RemoteConfigResponse fleetResponse, String configKey, Exception error) {
    RemoteConfigResponse.Targets.ConfigTarget target = fleetResponse.getTarget(configKey);
    RemoteConfigRequest.ClientInfo.ClientState.ConfigState newState =
        new RemoteConfigRequest.ClientInfo.ClientState.ConfigState();
    newState.setState(
        configKey,
        target.custom.version,
        product.name(),
        error != null ? error.getMessage() : null);
    configStates.put(configKey, newState);

    RemoteConfigRequest.CachedTargetFile newCTF =
        new RemoteConfigRequest.CachedTargetFile(configKey, target.length, target.hashes);
    cachedTargetFiles.put(configKey, newCTF);
  }

  public boolean hasError() {
    return error != null;
  }

  public ConfigurationPoller.ReportableException getError() {
    return error;
  }

  public Collection<RemoteConfigRequest.CachedTargetFile> getCachedTargetFiles() {
    return cachedTargetFiles.values();
  }

  public Collection<RemoteConfigRequest.ClientInfo.ClientState.ConfigState> getConfigStates() {
    return configStates.values();
  }
}
