package datadog.yaml;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public class YamlParser {
  public static Object parse(String content) {
    LoadSettings settings = LoadSettings.builder().setAllowDuplicateKeys(true).build();
    Load yaml = new Load(settings);
    return yaml.loadFromString(content);
  }
}
