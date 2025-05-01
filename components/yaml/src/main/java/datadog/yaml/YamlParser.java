package datadog.yaml;

import org.yaml.snakeyaml.Yaml;

public class YamlParser {
  // Supports clazz == null for default yaml parsing
  public static <T> T parse(String content, Class<T> clazz) {
    Yaml yaml = new Yaml();
    if (clazz == null) {
      return yaml.load(content);
    } else {
      return yaml.loadAs(content, clazz);
    }
  }
}
