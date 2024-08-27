package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.Strings.toHexString;

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
  private static final AtomicBoolean SHA_MISSING_REPORTED = new AtomicBoolean(false);

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
    TraceSegment segment = beforeEvent();
    if (segment == null) {
      return;
    }
    segment.setTagTop("appsec.events.users.login.failure.usr.exists", false);
  }

  public void onLoginSuccess(String userId, Map<String, String> metadata) {
    TraceSegment segment = beforeEvent(userId);
    if (segment == null) {
      return;
    }
    onUserId(segment, "usr.id", userId);
    onEvent(segment, "users.login.success", metadata);
  }

  public void onLoginFailure(String userId, Map<String, String> metadata) {
    TraceSegment segment = beforeEvent(userId);
    if (segment == null) {
      return;
    }
    onUserId(segment, "appsec.events.users.login.failure.usr.id", userId);
    onEvent(segment, "users.login.failure", metadata);
  }

  public void onSignup(String userId, Map<String, String> metadata) {
    TraceSegment segment = beforeEvent(userId);
    if (segment == null) {
      return;
    }
    onUserId(segment, "usr.id", userId);
    onEvent(segment, "users.signup", metadata);
  }

  /**
   * Check if we can report a login event
   *
   * @return current trace segment if we can report the event, {@code null} otherwise
   */
  private TraceSegment beforeEvent() {
    if (!isEnabled()) {
      return null;
    }
    return getSegment();
  }

  /**
   * Check if we can report a login event
   *
   * @param userId user id of the login event, if {@code null} the method will return {@code null}
   *     and report a telemetry metric
   * @see #beforeEvent()
   */
  private TraceSegment beforeEvent(String userId) {
    if (userId == null) {
      onMissingUserId();
      return null;
    }
    return beforeEvent();
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

  protected UserIdCollectionMode getUserIdCollectionMode() {
    return UserIdCollectionMode.get();
  }
}
