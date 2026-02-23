package datadog.trace.instrumentation.spark;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Extracts the AWS EMR Step ID from the working directory name (e.g. {@code s-0039...}). */
class EmrStepIdCapture {

  private static final Pattern EMR_STEP_ID_PATTERN = Pattern.compile("^(s-[0-9A-Za-z]+)$");

  @Nullable
  static String getEmrStepId() {
    String userDir = System.getProperty("user.dir");
    if (userDir != null) {
      Path workDir = Paths.get(userDir).getFileName();
      if (workDir != null) {
        Matcher matcher = EMR_STEP_ID_PATTERN.matcher(workDir.toString());
        if (matcher.matches()) {
          return matcher.group(1);
        }
      }
    }
    return null;
  }

  private EmrStepIdCapture() {}
}
