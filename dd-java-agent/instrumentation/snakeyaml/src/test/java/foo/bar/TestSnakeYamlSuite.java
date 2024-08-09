package foo.bar;

import java.io.InputStream;
import java.io.Reader;
import org.yaml.snakeyaml.Yaml;

public class TestSnakeYamlSuite {

  public static void init(final InputStream inputStream) {
    new Yaml().load(inputStream);
  }

  public static void init(final Reader reader) {
    new Yaml().load(reader);
  }

  public static void init(final String string) {
    new Yaml().load(string);
  }
}
