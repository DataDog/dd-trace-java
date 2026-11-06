package datadog.buildlogic.inlineofduty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.gradle.api.GradleException;

/** Selects test tasks reproducibly within one pipeline and rotates them across pipelines. */
final class InlineOfDutySample {
  private InlineOfDutySample() {}

  static boolean selected(boolean enabled, int percent, String taskPath, String pipelineId) {
    if (percent < 0 || percent > 100) {
      throw new GradleException("inlineOfDutySamplePercent must be between 0 and 100");
    }
    if (!enabled || percent == 0) return false;
    if (percent == 100) return true;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(pipelineId.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      byte[] hash = digest.digest(taskPath.getBytes(StandardCharsets.UTF_8));
      long bucket = 0;
      for (int i = 0; i < 8; i++) bucket = (bucket << 8) | (hash[i] & 0xffL);
      return Long.remainderUnsigned(bucket, 100) < percent;
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("JVM lacks SHA-256", failure);
    }
  }
}
