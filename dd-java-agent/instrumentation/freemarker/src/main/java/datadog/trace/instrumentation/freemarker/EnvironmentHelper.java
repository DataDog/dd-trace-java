package datadog.trace.instrumentation.freemarker;

import freemarker.core.Environment;
import freemarker.template.TemplateHashModel;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EnvironmentHelper {
  private EnvironmentHelper() {}

  private static final Logger log = LoggerFactory.getLogger(EnvironmentHelper.class);

  private static final Field ROOT_DATA_MODEL = prepareRootDataModel();

  private static Field prepareRootDataModel() {
    Field rootDataModel = null;
    try {
      rootDataModel = Environment.class.getDeclaredField("rootDataModel");
      rootDataModel.setAccessible(true);
    } catch (Throwable e) {
      log.debug("Failed to get DollarVariable expression", e);
      return null;
    }
    return rootDataModel;
  }

  public static TemplateHashModel fetchRootDataModel(Environment environment) {
    if (ROOT_DATA_MODEL == null) {
      return null;
    }
    try {
      return (TemplateHashModel) ROOT_DATA_MODEL.get(environment);
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}
