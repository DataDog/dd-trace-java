package datadog.trace.bootstrap.config.provider.stableconfigyaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

// Rule represents a set of selectors and their corresponding configurations found in stable
// configuration files
public class Rule {
  private List<Selector> selectors;
  private Map<String, Object> configuration;

  public Rule() {
    this.selectors = new ArrayList<>();
    this.configuration = new LinkedHashMap<>();
  }

  public Rule(List<Selector> selectors, Map<String, Object> configuration) {
    this.selectors = selectors;
    this.configuration = configuration;
  }

  public Rule(Object yaml) {
    Map map = (Map) yaml;
    selectors =
        ((List<Object>) map.get("selectors"))
            .stream().filter(Objects::nonNull).map(Selector::new).collect(Collectors.toList());
    configuration = (Map<String, Object>) map.get("configuration");
  }

  public List<Selector> getSelectors() {
    return selectors;
  }

  public Map<String, Object> getConfiguration() {
    return configuration;
  }
}
