package datadog.config.stableconfig;

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

  public Rule(Object yaml) {
    Map map = (Map) yaml;
    selectors =
        unmodifiableList(
            ((List<Object>) map.get("selectors"))
                .stream().filter(Objects::nonNull).map(Selector::new).collect(toList()));
    configuration = unmodifiableMap((Map<String, Object>) map.get("configuration"));
  }

  public List<Selector> getSelectors() {
    return selectors;
  }

  public Map<String, Object> getConfiguration() {
    return configuration;
  }
}
