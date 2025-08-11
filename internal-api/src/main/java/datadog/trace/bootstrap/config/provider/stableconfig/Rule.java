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
      StableConfigMappingException.throwStableConfigMappingException(
          "Missing 'selectors' in rule", map);
    }

    if (!(selectorsObj instanceof List)) {
      StableConfigMappingException.throwStableConfigMappingException(
          "'selectors' must be a list, but got: " + selectorsObj.getClass().getSimpleName(),
          selectorsObj);
    }

    List<Selector> selectors =
        unmodifiableList(
            ((List<?>) selectorsObj)
                .stream()
                    .filter(Objects::nonNull)
                    .map(
                        s -> {
                          if (s instanceof Map) {
                            return Selector.from((Map<?, ?>) s);
                          }
                          StableConfigMappingException.throwStableConfigMappingException(
                              "Each selector must be a map, but got: "
                                  + s.getClass().getSimpleName(),
                              s);
                          return null;
                        })
                    .collect(toList()));

    Object configObj = map.get("configuration");
    if (configObj == null) {
      StableConfigMappingException.throwStableConfigMappingException(
          "Missing 'configuration' in rule", map);
    }
    if (!(configObj instanceof Map)) {
      StableConfigMappingException.throwStableConfigMappingException(
          "'configuration' must be a map, but got: " + configObj.getClass().getSimpleName(),
          configObj);
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
