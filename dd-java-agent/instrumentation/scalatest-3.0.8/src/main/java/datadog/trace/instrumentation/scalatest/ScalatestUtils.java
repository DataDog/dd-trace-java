package datadog.trace.instrumentation.scalatest;

import datadog.trace.api.civisibility.config.LibraryCapability;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import javax.annotation.Nullable;
import org.scalatest.Reporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

public abstract class ScalatestUtils {

  private static final Logger log = LoggerFactory.getLogger(ScalatestUtils.class);

  private static final ClassLoader CLASS_LOADER = Reporter.class.getClassLoader();

  public static final List<LibraryCapability> CAPABILITIES =
      Arrays.asList(
          LibraryCapability.TIA,
          LibraryCapability.EFD,
          LibraryCapability.ATR,
          LibraryCapability.IMPACTED,
          LibraryCapability.FTR,
          LibraryCapability.QUARANTINE,
          LibraryCapability.DISABLED,
          LibraryCapability.ATTEMPT_TO_FIX);

  private ScalatestUtils() {}

  public static @Nullable String getScalatestVersion() {
    try {
      String className = '/' + Reporter.class.getName().replace('.', '/') + ".class";
      URL classResource = Reporter.class.getResource(className);
      if (classResource == null) {
        return null;
      }

      String classPath = classResource.toString();
      String manifestPath =
          classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
      try (InputStream manifestStream = new java.net.URL(manifestPath).openStream()) {
        Properties manifestProperties = new Properties();
        manifestProperties.load(manifestStream);
        return manifestProperties.getProperty("Bundle-Version");
      }

    } catch (Exception e) {
      return null;
    }
  }

  @Nullable
  public static Class<?> getClass(Option<String> className) {
    if (className.isEmpty()) {
      return null;
    }
    try {
      return CLASS_LOADER.loadClass(className.get());
    } catch (Exception e) {
      log.debug("Could not load class {}", className, e);
      log.warn("Could not load a Scalatest class");
      return null;
    }
  }
}
