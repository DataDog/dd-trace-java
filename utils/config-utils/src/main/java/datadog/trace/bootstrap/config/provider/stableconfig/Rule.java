package datadog.trace.bootstrap.config.provider.stableconfig;

import static datadog.trace.bootstrap.config.provider.stableconfig.StableConfigMappingException.throwStableConfigMappingException;
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
      throwStableConfigMappingException("Missing 'selectors' in rule:", map);
    }

    if (!(selectorsObj instanceof List)) {
      throwStableConfigMappingException(
          "'selectors' must be a list, but got: " + selectorsObj.getClass().getSimpleName() + ": ",
          selectorsObj);
    }

    Object configObj = map.get("configuration");
    if (configObj == null) {
      throwStableConfigMappingException("Missing 'configuration' in rule:", map);
    }
    if (!(configObj instanceof Map)) {
      throwStableConfigMappingException(
          "'configuration' must be a map, but got: " + configObj.getClass().getSimpleName() + ": ",
          configObj);
    }

    List<Selector> selectors =
        ((List<?>) selectorsObj)
            .stream()
                .filter(Objects::nonNull)
                .map(
                    s -> {
                      if (!(s instanceof Map)) {
                        throwStableConfigMappingException(
                            "Each selector must be a map, but got: "
                                + s.getClass().getSimpleName()
                                + ": ",
                            s);
                      }

                      return Selector.from((Map<?, ?>) s);
                    })
                .collect(toList());

    return new Rule(unmodifiableList(selectors), (Map<String, Object>) configObj);
  }

  public List<Selector> getSelectors() {
    return selectors;
  }

  public Map<String, Object> getConfiguration() {
    return configuration;
  }
}
