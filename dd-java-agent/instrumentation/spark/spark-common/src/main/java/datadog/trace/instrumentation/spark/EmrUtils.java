package datadog.trace.instrumentation.spark;

import java.nio.file.Path;
import java.nio.file.Paths;
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
   * Returns true if the Spark job is running on AWS EMR. Detection uses two EMR-exclusive keys:
   *
   * <ul>
   *   <li>{@code spark.sql.emr.internal.extensions}: registers EMR's Spark session extensions,
   *       present across all known EMR releases (observed in community EMR configs)
   *   <li>{@code spark.emr.default.executor.cores}: newer EMR default, added as a fallback for
   *       future releases where the extensions key might change
   * </ul>
   */
  static boolean isRunningOnEmr(SparkConf conf) {
    return conf.contains("spark.sql.emr.internal.extensions")
        || conf.contains("spark.emr.default.executor.cores");
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
