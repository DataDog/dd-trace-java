package datadog.trace.instrumentation.spark;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utilities for detecting AWS EMR and extracting EMR-specific metadata. */
class EmrUtils {

  private static final Logger log = LoggerFactory.getLogger(EmrUtils.class);

  /** EMR step ID is a 20 character string with numbers and uppercase letters only */
  private static final Pattern EMR_STEP_ID_PATTERN = Pattern.compile("^(s-[0-9A-Z]{20})$");

  /**
   * Returns true if the Spark job is running on AWS EMR. Detection scans all SparkConf keys for the
   * {@code .emr.} substring, which is present in EMR-specific keys (e.g. {@code
   * spark.emr.default.executor.cores}, {@code spark.sql.emr.internal.extensions}) and absent from
   * all standard Spark configuration keys.
   */
  static boolean isRunningOnEmr(SparkConf conf) {
    return Arrays.stream(conf.getAll()).anyMatch(t -> t._1().contains(".emr."));
  }

  @Nullable
  static String getEmrStepId() {
    try {
      String userDir = System.getProperty("user.dir");
      if (userDir != null) {
        Path workDir = Paths.get(userDir).getFileName();
        if (workDir != null) {
          Matcher matcher = EMR_STEP_ID_PATTERN.matcher(workDir.toString());
          if (matcher.matches()) {
            log.debug("EMR step ID extracted: {}", matcher.group(1));
            return matcher.group(1);
          }
        }
      }
    } catch (Throwable t) {
      log.debug("Unable to extract EMR step ID from working directory", t);
    }
    return null;
  }

  private EmrUtils() {}
}
