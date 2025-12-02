package datadog.trace.instrumentation.weaver;

import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.util.MethodHandles;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import weaver.Result;
import weaver.framework.SbtTask;

public abstract class WeaverUtils {

  private static final Logger log = LoggerFactory.getLogger(WeaverUtils.class);

  private static final ClassLoader CLASS_LOADER = SbtTask.class.getClassLoader();
  public static final MethodHandles METHOD_HANDLES = new MethodHandles(CLASS_LOADER);

  // Reflection used due to changes in Weaver v0.11:
  // - Result.Cancelled was removed
  private static final String RESULT_CANCELLED_CLASS_NAME = "weaver.Result$Cancelled";
  public static final MethodHandle GET_CANCELLED_REASON_HANDLE =
      METHOD_HANDLES.method(getClass(RESULT_CANCELLED_CLASS_NAME), "reason");
  // - Ignore.reason() changed from Optional<String> to String
  public static final MethodHandle GET_IGNORED_REASON_HANDLE =
      METHOD_HANDLES.method(Result.Ignored.class, "reason");
  // - Result.Failure changed to Result.Failures.Failure
  private static final String RESULT_FAILURE_CLASS_NAME = "weaver.Result$Failure";
  private static final String RESULT_FAILURES_FAILURE_CLASS_NAME = "weaver.Result$Failures$Failure";
  // - Failure.source changed from Optional<Throwable> to Throwable
  public static final MethodHandle GET_FAILURE_SOURCE_HANDLE = createFailureSourceHandle();

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

  @Nullable
  private static MethodHandle createFailureSourceHandle() {
    Class<?> newFailureClass = getClass(RESULT_FAILURES_FAILURE_CLASS_NAME);
    if (newFailureClass != null) {
      return METHOD_HANDLES.method(newFailureClass, "source");
    }
    // Fallback to old location (Result.Failure)
    return METHOD_HANDLES.method(getClass(RESULT_FAILURE_CLASS_NAME), "source");
  }

  public static boolean isResultFailure(@Nonnull Result result) {
    String className = result.getClass().getName();
    return RESULT_FAILURE_CLASS_NAME.equals(className)
        || RESULT_FAILURES_FAILURE_CLASS_NAME.equals(className);
  }

  public static boolean isResultCancelled(@Nonnull Result result) {
    String className = result.getClass().getName();
    return RESULT_CANCELLED_CLASS_NAME.equals(className);
  }

  public static <T> T unwrap(Object value, Class<T> type) {
    if (value instanceof Option) {
      return ((Option<?>) value).getOrElse(null);
    } else {
      return type.cast(value);
    }
  }
}
