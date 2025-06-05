package datadog.trace.instrumentation.weaver;

import datadog.trace.api.civisibility.config.LibraryCapability;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weaver.framework.SbtTask;

public abstract class WeaverUtils {

  private static final Logger log = LoggerFactory.getLogger(WeaverUtils.class);

  private static final ClassLoader CLASS_LOADER = SbtTask.class.getClassLoader();

  public static final List<LibraryCapability> CAPABILITIES = Collections.emptyList();

  private WeaverUtils() {}

  public static @Nullable String getWeaverVersion() {
    try {
      String className = '/' + SbtTask.class.getName().replace('.', '/') + ".class";
      URL classResource = SbtTask.class.getResource(className);
      if (classResource == null) {
        return null;
      }

      String classPath = classResource.toString();
      String manifestPath =
          classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
      try (InputStream manifestStream = new URL(manifestPath).openStream()) {
        Properties manifestProperties = new Properties();
        manifestProperties.load(manifestStream);
        return manifestProperties.getProperty("Implementation-Version");
      }
    } catch (Exception e) {
      return null;
    }
  }

  @Nullable
  public static Class<?> getClass(String className) {
    if (className.isEmpty()) {
      return null;
    }
    try {
      return CLASS_LOADER.loadClass(className);
    } catch (Exception e) {
      log.debug("Could not load class {}", className, e);
      return null;
    }
  }
}
