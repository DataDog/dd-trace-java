package datadog.trace.instrumentation.spark;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Extracts the AWS EMR Step ID from the working directory name (e.g. s-07767992IY7VC5NVV854). */
class EmrUtils {

  private static final Logger log = LoggerFactory.getLogger(EmrUtils.class);

  /** EMR step ID is a 20 character string with numbers and uppercase letters only */
  private static final Pattern EMR_STEP_ID_PATTERN = Pattern.compile("^(s-[0-9A-Z]{20})$");

  @Nullable
  static String getEmrStepId() {
    try {
      String userDir = System.getProperty("user.dir");
      if (userDir == null) {
        return null;
      }
      Path workDir = Paths.get(userDir).getFileName();
      if (workDir == null) {
        return null;
      }
      Matcher matcher = EMR_STEP_ID_PATTERN.matcher(workDir.toString());
      if (matcher.matches()) {
        log.debug("EMR step ID extracted: {}", matcher.group(1));
        return matcher.group(1);
      }
    } catch (Throwable t) {
      log.debug("Unable to extract EMR step ID from working directory", t);
    }
    return null;
  }

  private EmrUtils() {}
}
