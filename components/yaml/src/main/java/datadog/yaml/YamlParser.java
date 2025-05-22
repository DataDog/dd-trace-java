package datadog.yaml;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class YamlParser {
  /**
   * Parses YAML content. Duplicate keys are not allowed and will result in a runtime exception..
   *
   * @param content - text context to be parsed as YAML
   * @return - a parsed representation as a composition of map and list objects.
   */
  public static Object parse(String content) {
    LoadSettings settings = LoadSettings.builder().build();
    Load yaml = new Load(settings);
    return yaml.loadFromString(content);
  }
}
