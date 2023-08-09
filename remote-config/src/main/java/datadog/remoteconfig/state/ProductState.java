package datadog.remoteconfig.state;

import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.tuf.MissingContentException;
import datadog.remoteconfig.tuf.RemoteConfigRequest;
import datadog.remoteconfig.tuf.RemoteConfigResponse;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProductState {
  private static final Logger log = LoggerFactory.getLogger(ProductState.class);
  private static final int MINUTES_BETWEEN_ERROR_LOG = 5;
  private final RatelimitedLogger ratelimitedLogger;

  final Product product;

  private final Map<ParsedConfigKey, RemoteConfigRequest.CachedTargetFile> cachedTargetFiles =
      new HashMap<>();
  private final Map<ParsedConfigKey, RemoteConfigRequest.ClientInfo.ClientState.ConfigState>
      configStates = new HashMap<>();
  private final ProductListener listener;

  List<ConfigurationPoller.ReportableException> errors = null;

  public ProductState(Product product, ProductListener listener) {
    this.product = product;
    this.listener = listener;

    this.ratelimitedLogger =
        new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES);
  }

  public boolean apply(
      RemoteConfigResponse fleetResponse,
      List<ParsedConfigKey> relevantKeys,
      ConfigurationChangesListener.PollingRateHinter hinter) {
    errors = null;

    List<ParsedConfigKey> configBeenUsedByProduct = new ArrayList<>();
    boolean changesDetected = false;

    for (ParsedConfigKey configKey : relevantKeys) {
      try {
        RemoteConfigResponse.Targets.ConfigTarget target =
            getTargetOrThrow(fleetResponse, configKey);
        configBeenUsedByProduct.add(configKey);

        if (isTargetChanged(configKey, target)) {
          changesDetected = true;
          byte[] content = getTargetFileContent(fleetResponse, configKey);
          callListenerApplyTarget(fleetResponse, hinter, configKey, content);
        }
      } catch (ConfigurationPoller.ReportableException e) {
        recordError(e);
      }
    }

    List<ParsedConfigKey> keysToRemove =
        cachedTargetFiles.keySet().stream()
            .filter(configKey -> !configBeenUsedByProduct.contains(configKey))
            .collect(Collectors.toList());

    for (ParsedConfigKey configKey : keysToRemove) {
      changesDetected = true;
      callListenerRemoveTarget(hinter, configKey);
    }

    if (changesDetected) {
      try {
        callListenerCommit(hinter);
      } catch (Exception ex) {
        log.error("Error committing changes for product {}", product, ex);
      }
    }

    return changesDetected;
  }

  private void callListenerApplyTarget(
      RemoteConfigResponse fleetResponse,
      ConfigurationChangesListener.PollingRateHinter hinter,
      ParsedConfigKey configKey,
      byte[] content) {

    try {
      listener.accept(configKey, content, hinter);
      updateConfigState(fleetResponse, configKey, null);
    } catch (ConfigurationPoller.ReportableException e) {
      recordError(e);
    } catch (Exception ex) {
      updateConfigState(fleetResponse, configKey, ex);
      if (!(ex instanceof InterruptedIOException)) {
        ratelimitedLogger.warn(
            "Error processing config key {}: {}", configKey, ex.getMessage(), ex);
      }
    }
  }

  private void callListenerRemoveTarget(
      ConfigurationChangesListener.PollingRateHinter hinter, ParsedConfigKey configKey) {
    try {
      listener.remove(configKey, hinter);
    } catch (Exception ex) {
      ratelimitedLogger.warn("Error handling configuration removal for " + configKey, ex);
    }
    cachedTargetFiles.remove(configKey);
    configStates.remove(configKey);
  }

  private void callListenerCommit(ConfigurationChangesListener.PollingRateHinter hinter) {
    try {
      listener.commit(hinter);
    } catch (ConfigurationPoller.ReportableException e) {
      recordError(e);
    } catch (Exception ex) {
      ratelimitedLogger.warn(
          "Error committing changes for product {}: {}", product, ex.getMessage(), ex);
    }
  }

  RemoteConfigResponse.Targets.ConfigTarget getTargetOrThrow(
      RemoteConfigResponse fleetResponse, ParsedConfigKey configKey) {
    RemoteConfigResponse.Targets.ConfigTarget target =
        fleetResponse.getTarget(configKey.toString());
    if (target == null) {
      throw new ConfigurationPoller.ReportableException(
          "Told to apply config for "
              + configKey
              + " but no corresponding entry exists in targets.targets_signed.targets");
    }
    return target;
  }

  boolean isTargetChanged(
      ParsedConfigKey parsedConfigKey, RemoteConfigResponse.Targets.ConfigTarget target) {
    RemoteConfigRequest.CachedTargetFile cachedTargetFile = cachedTargetFiles.get(parsedConfigKey);
    if (cachedTargetFile != null && cachedTargetFile.hashesMatch(target.hashes)) {
      log.debug("No change in configuration for key {}", parsedConfigKey);
      return false;
    }
    return true;
  }

  byte[] getTargetFileContent(RemoteConfigResponse fleetResponse, ParsedConfigKey configKey) {
    // fetch the content
    byte[] maybeFileContent;
    try {
      maybeFileContent = fleetResponse.getFileContents(configKey.toString());
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
      RemoteConfigResponse fleetResponse, ParsedConfigKey parsedConfigKey, Exception error) {
    String configKey = parsedConfigKey.toString();
    RemoteConfigResponse.Targets.ConfigTarget target = fleetResponse.getTarget(configKey);
    RemoteConfigRequest.ClientInfo.ClientState.ConfigState newState =
        new RemoteConfigRequest.ClientInfo.ClientState.ConfigState();
    newState.setState(
        parsedConfigKey.getConfigId(),
        target.custom.version,
        product.name(),
        error != null ? error.getMessage() : null);
    configStates.put(parsedConfigKey, newState);

    RemoteConfigRequest.CachedTargetFile newCTF =
        new RemoteConfigRequest.CachedTargetFile(configKey, target.length, target.hashes);
    cachedTargetFiles.put(parsedConfigKey, newCTF);
  }

  public boolean hasError() {
    return errors != null;
  }

  public void recordError(ConfigurationPoller.ReportableException error) {
    if (errors == null) {
      errors = new ArrayList<>();
    }
    errors.add(error);
  }

  public Collection<ConfigurationPoller.ReportableException> getErrors() {
    return errors;
  }

  public Collection<RemoteConfigRequest.CachedTargetFile> getCachedTargetFiles() {
    return cachedTargetFiles.values();
  }

  public Collection<RemoteConfigRequest.ClientInfo.ClientState.ConfigState> getConfigStates() {
    return configStates.values();
  }
}
