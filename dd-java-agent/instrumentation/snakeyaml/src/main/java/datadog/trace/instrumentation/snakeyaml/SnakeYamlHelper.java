package datadog.trace.instrumentation.snakeyaml;

import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.BaseConstructor;

public final class SnakeYamlHelper {
  private SnakeYamlHelper() {}

  private static final Logger log = LoggerFactory.getLogger(SnakeYamlHelper.class);

  private static final Field CONSTRUCTOR = prepareConstructor();

  private static Field prepareConstructor() {
    Field constructor = null;
    try {
      constructor = Yaml.class.getDeclaredField("constructor");
      constructor.setAccessible(true);
    } catch (Throwable e) {
      log.debug("Failed to get Yaml constructor", e);
      return null;
    }
    return constructor;
  }

  public static BaseConstructor fetchConstructor(Yaml yaml) {
    if (CONSTRUCTOR == null) {
      return null;
    }
    try {
      return (BaseConstructor) CONSTRUCTOR.get(yaml);
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}
