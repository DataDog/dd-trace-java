package datadog.trace.bootstrap.config.provider.stableconfig;

import static java.util.Collections.*;
import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rule represents a set of selectors and their corresponding configurations found in stable
 * configuration files
 */
public final class Rule {
  private final List<Selector> selectors;
  private final Map<String, Object> configuration;

  public Rule() {
    this.selectors = emptyList();
    this.configuration = emptyMap();
  }

  public Rule(List<Selector> selectors, Map<String, Object> configuration) {
    this.selectors = selectors;
    this.configuration = configuration;
  }

  public static Rule from(Map<?, ?> map) {
    Object selectorsObj = map.get("selectors");
    if (selectorsObj == null) {
      throw new StableConfigMappingException(
          "Missing 'selectors' in rule: " + StableConfigMappingException.safeToString(map));
    }

    if (!(selectorsObj instanceof List)) {
      throw new StableConfigMappingException(
          "'selectors' must be a list, but got: "
              + selectorsObj.getClass().getSimpleName()
              + ", value: "
              + StableConfigMappingException.safeToString(selectorsObj));
    }

    List<Selector> selectors =
        unmodifiableList(
            ((List<?>) selectorsObj)
                .stream()
                    .filter(Objects::nonNull)
                    .map(
                        s -> {
                          if (!(s instanceof Map)) {
                            throw new StableConfigMappingException(
                                "Each selector must be a map, but got: "
                                    + s.getClass().getSimpleName()
                                    + ", value: "
                                    + StableConfigMappingException.safeToString(s));
                          }
                          return Selector.from((Map<?, ?>) s);
                        })
                    .collect(toList()));

    Object configObj = map.get("configuration");
    if (configObj == null) {
      throw new StableConfigMappingException(
          "Missing 'configuration' in rule: " + StableConfigMappingException.safeToString(map));
    }
    if (!(configObj instanceof Map)) {
      throw new StableConfigMappingException(
          "'configuration' must be a map, but got: "
              + configObj.getClass().getSimpleName()
              + ", value: "
              + StableConfigMappingException.safeToString(configObj));
    }
    Map<String, Object> configuration = (Map<String, Object>) configObj;

    return new Rule(selectors, configuration);
  }

  public List<Selector> getSelectors() {
    return selectors;
  }

  public Map<String, Object> getConfiguration() {
    return configuration;
  }
}
