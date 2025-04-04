package datadog.yaml;

import java.io.FileInputStream;
import java.io.IOException;
import org.yaml.snakeyaml.Yaml;

public class YamlParser {
  // Supports clazz == null for default yaml parsing
  public static <T> T parse(String filePath, Class<T> clazz) throws IOException {
    Yaml yaml = new Yaml();
    try (FileInputStream fis = new FileInputStream(filePath)) {
      if (clazz == null) {
        return yaml.load(fis);
      } else {
        return yaml.loadAs(fis, clazz);
      }
    }
  }
}
