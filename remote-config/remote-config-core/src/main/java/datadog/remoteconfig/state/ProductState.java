package datadog.remoteconfig.state;

import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.ReportableException;
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
  private final List<ProductListener> productListeners;
  private final Map<String, ProductListener> configListeners;

  List<ReportableException> errors = null;

  public ProductState(Product product) {
    this.product = product;
    this.productListeners = new ArrayList<>();
    this.configListeners = new HashMap<>();

    this.ratelimitedLogger =
        new RatelimitedLogger(log, MINUTES_BETWEEN_ERROR_LOG, TimeUnit.MINUTES);
  }

  public void addProductListener(ProductListener listener) {
    productListeners.add(listener);
  }

  public void addProductListener(String configId, ProductListener listener) {
    configListeners.put(configId, listener);
  }

  public boolean apply(
      RemoteConfigResponse fleetResponse,
      List<ParsedConfigKey> relevantKeys,
      PollingRateHinter hinter) {
    errors = null;

    List<ParsedConfigKey> configBeenUsedByProduct = new ArrayList<>();
    List<ParsedConfigKey> changedKeys = new ArrayList<>();
    boolean changesDetected = false;

    // Step 1: Detect all changes
    for (ParsedConfigKey configKey : relevantKeys) {
      try {
        RemoteConfigResponse.Targets.ConfigTarget target =
            getTargetOrThrow(fleetResponse, configKey);
        configBeenUsedByProduct.add(configKey);

        if (isTargetChanged(configKey, target)) {
          changesDetected = true;
          changedKeys.add(configKey);
        }
      } catch (ReportableException e) {
        recordError(e);
      }
    }

    // Step 2: For products other than ASM_DD, apply changes immediately
    if (product != Product.ASM_DD) {
      for (ParsedConfigKey configKey : changedKeys) {
        try {
          byte[] content = getTargetFileContent(fleetResponse, configKey);
          callListenerApplyTarget(fleetResponse, hinter, configKey, content);
        } catch (ReportableException e) {
          recordError(e);
        }
      }
    }

    // Step 3: Remove obsolete configurations (for all products)
    // For ASM_DD, this is critical: removes MUST happen before applies to prevent
    // duplicate rule warnings from the ddwaf rule parser and causing memory spikes.
    List<ParsedConfigKey> keysToRemove =
        cachedTargetFiles.keySet().stream()
            .filter(configKey -> !configBeenUsedByProduct.contains(configKey))
            .collect(Collectors.toList());

    for (ParsedConfigKey configKey : keysToRemove) {
      changesDetected = true;
      callListenerRemoveTarget(hinter, configKey);
    }

    // Step 4: For ASM_DD, apply changes AFTER removes
    // TODO: This is a temporary solution. The proper fix requires better synchronization
    // between remove and add/update operations. This should be discussed
    // with the guild to determine the best long-term design approach.
    if (product == Product.ASM_DD) {
      for (ParsedConfigKey configKey : changedKeys) {
        try {
          byte[] content = getTargetFileContent(fleetResponse, configKey);
          callListenerApplyTarget(fleetResponse, hinter, configKey, content);
        } catch (ReportableException e) {
          recordError(e);
        }
      }
    }

    // Step 5: Commit if there were changes
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
      PollingRateHinter hinter,
      ParsedConfigKey configKey,
      byte[] content) {

    try {
      for (ProductListener listener : productListeners) {
        listener.accept(configKey, content, hinter);
      }
      ProductListener listener = configListeners.get(configKey.getConfigId());
      if (listener != null) {
        listener.accept(configKey, content, hinter);
      }

      updateConfigState(fleetResponse, configKey, null);
    } catch (ReportableException e) {
      recordError(e);
    } catch (Exception ex) {
      updateConfigState(fleetResponse, configKey, ex);
      if (!(ex instanceof InterruptedIOException)) {
        ratelimitedLogger.warn(
            "Error processing config key {}: {}", configKey, ex.getMessage(), ex);
      }
    }
  }

  private void callListenerRemoveTarget(PollingRateHinter hinter, ParsedConfigKey configKey) {
    try {
      for (ProductListener listener : productListeners) {
        listener.remove(configKey, hinter);
      }
      ProductListener listener = configListeners.get(configKey.getConfigId());
      if (listener != null) {
        listener.remove(configKey, hinter);
      }
    } catch (Exception ex) {
      ratelimitedLogger.warn("Error handling configuration removal for {}", configKey, ex);
    }
    cachedTargetFiles.remove(configKey);
    configStates.remove(configKey);
  }

  private void callListenerCommit(PollingRateHinter hinter) {
    try {
      for (ProductListener listener : productListeners) {
        listener.commit(hinter);
      }
      for (ProductListener listener : configListeners.values()) {
        listener.commit(hinter);
      }
    } catch (ReportableException e) {
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
      throw new ReportableException(
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
        throw new ReportableException(
            "Told to apply config "
                + configKey
                + " but content not present even though hash differs from that of 'cached file'");
      }
      throw new ReportableException(e.getMessage());
    } catch (Exception e) {
      throw new ReportableException(e.getMessage());
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

  public void recordError(ReportableException error) {
    if (errors == null) {
      errors = new ArrayList<>();
    }
    errors.add(error);
  }

  public Collection<ReportableException> getErrors() {
    return errors;
  }

  public Collection<RemoteConfigRequest.CachedTargetFile> getCachedTargetFiles() {
    return cachedTargetFiles.values();
  }

  public Collection<RemoteConfigRequest.ClientInfo.ClientState.ConfigState> getConfigStates() {
    return configStates.values();
  }
}
