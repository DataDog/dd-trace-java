package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.Strings.toHexString;

import datadog.trace.api.Config;
import datadog.trace.api.UserIdCollectionMode;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecUserEventDecorator {

  private static final Logger LOGGER = LoggerFactory.getLogger(AppSecUserEventDecorator.class);
  private static final int HASH_SIZE_BYTES = 16; // 128 bits
  private static final String ANONYM_PREFIX = "anon_";
  private static AtomicBoolean SHA_MISSING_REPORTED = new AtomicBoolean(false);

  public boolean isEnabled() {
    if (!ActiveSubsystems.APPSEC_ACTIVE) {
      return false;
    }
    if (getUserIdCollectionMode() == UserIdCollectionMode.DISABLED) {
      return false;
    }
    return true;
  }

  public void onUserNotFound() {
    if (!isEnabled()) {
      return;
    }
    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }
    segment.setTagTop("appsec.events.users.login.failure.usr.exists", false);
  }

  public void onLoginSuccess(String userId, Map<String, String> metadata) {
    if (userId == null) {
      onMissingUserId();
      return;
    }

    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }

    onUserId(segment, "usr.id", userId);
    onEvent(segment, "users.login.success", metadata);
  }

  public void onLoginFailure(String userId, Map<String, String> metadata) {
    if (userId == null) {
      onMissingUserId();
      return;
    }

    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }

    onUserId(segment, "appsec.events.users.login.failure.usr.id", userId);
    onEvent(segment, "users.login.failure", metadata);
  }

  public void onSignup(String userId, Map<String, String> metadata) {
    if (userId == null) {
      onMissingUserId();
      return;
    }

    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }

    onUserId(segment, "usr.id", userId);
    onEvent(segment, "users.signup", metadata);
  }

  private void onEvent(@Nonnull TraceSegment segment, String eventName, Map<String, String> tags) {
    segment.setTagTop("appsec.events." + eventName + ".track", true, true);
    segment.setTagTop(Tags.ASM_KEEP, true);
    segment.setTagTop(Tags.PROPAGATED_APPSEC, true);

    // Report user event tracking mode ("identification" or "anonymization")
    UserIdCollectionMode mode = getUserIdCollectionMode();
    if (mode != UserIdCollectionMode.DISABLED) {
      segment.setTagTop("_dd.appsec.events." + eventName + ".auto.mode", mode.toString());
    }

    if (tags != null && !tags.isEmpty()) {
      segment.setTagTop("appsec.events." + eventName, tags);
    }
  }

  /** Takes care of user anonymization if required. */
  protected void onUserId(final TraceSegment segment, final String tagName, final String userId) {
    if (segment.getTagTop(tagName) != null) {
      // do not override user ids set by the SDK
      return;
    }
    String finalUserId =
        getUserIdCollectionMode() == UserIdCollectionMode.ANONYMIZATION
            ? anonymize(userId)
            : userId;
    segment.setTagTop(tagName, finalUserId);
  }

  protected static String anonymize(String userId) {
    if (userId == null) {
      return null;
    }
    MessageDigest digest;
    try {
      // TODO avoid lookup a new instance every time
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      if (!SHA_MISSING_REPORTED.getAndSet(true)) {
        LOGGER.error(
            SEND_TELEMETRY,
            "Missing SHA-256 digest, user collection in 'anon' mode cannot continue",
            e);
      }
      return null;
    }
    digest.update(userId.getBytes());
    byte[] hash = digest.digest();
    if (hash.length > HASH_SIZE_BYTES) {
      byte[] temp = new byte[HASH_SIZE_BYTES];
      System.arraycopy(hash, 0, temp, 0, temp.length);
      hash = temp;
    }
    return ANONYM_PREFIX + toHexString(hash);
  }

  protected TraceSegment getSegment() {
    return AgentTracer.get().getTraceSegment();
  }

  protected void onMissingUserId() {
    WafMetricCollector.get().missingUserId();
  }

  /** TODO link with remote config when ready */
  protected UserIdCollectionMode getUserIdCollectionMode() {
    return Config.get().getAppSecUserIdCollectionMode();
  }
}
