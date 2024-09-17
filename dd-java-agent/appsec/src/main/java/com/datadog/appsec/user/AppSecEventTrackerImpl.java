package com.datadog.appsec.user;

import static datadog.trace.api.UserIdCollectionMode.ANONYMIZATION;
import static datadog.trace.api.UserIdCollectionMode.DISABLED;
import static datadog.trace.api.UserIdCollectionMode.SDK;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.Strings.toHexString;

import datadog.trace.api.UserIdCollectionMode;
import datadog.trace.api.appsec.AppSecEventTracker;
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

public class AppSecEventTrackerImpl extends AppSecEventTracker {

  private static final Logger LOGGER = LoggerFactory.getLogger(AppSecEventTrackerImpl.class);
  private static final int HASH_SIZE_BYTES = 16; // 128 bits
  private static final String ANON_PREFIX = "anon_";
  private static final AtomicBoolean SHA_MISSING_REPORTED = new AtomicBoolean(false);

  private static final String LOGIN_FAILURE_NO_USER_TAG =
      "appsec.events.users.login.failure.usr.exists";
  private static final String LOGIN_FAILURE_USER_ID_EXTRA_TAG =
      "appsec.events.users.login.failure.usr.id";
  private static final String USER_COLLECTION_MODE_TAG = "_dd.appsec.user.collection_mode";

  protected boolean isEnabled(final UserIdCollectionMode mode) {
    return ActiveSubsystems.APPSEC_ACTIVE && mode != DISABLED;
  }

  @Override
  public void onUserNotFound(final UserIdCollectionMode mode) {
    TraceSegment segment = beforeEvent(mode);
    if (segment == null) {
      return;
    }
    segment.setTagTop(LOGIN_FAILURE_NO_USER_TAG, false);
  }

  @Override
  public void onSignupEvent(
      final UserIdCollectionMode mode, final String userId, final Map<String, String> metadata) {
    TraceSegment segment = beforeEvent(mode, userId);
    if (segment == null) {
      return;
    }
    onUserId(mode, segment, userId);
    onEvent(mode, segment, "users.signup", false, metadata);
  }

  @Override
  public void onLoginSuccessEvent(
      final UserIdCollectionMode mode, final String userId, final Map<String, String> metadata) {
    TraceSegment segment = beforeEvent(mode, userId);
    if (segment == null) {
      return;
    }
    onUserId(mode, segment, userId);
    onEvent(mode, segment, "users.login.success", false, metadata);
  }

  @Override
  public void onLoginFailureEvent(
      final UserIdCollectionMode mode,
      final String userId,
      final Boolean exists,
      final Map<String, String> metadata) {
    TraceSegment segment = beforeEvent(mode, userId);
    if (segment == null) {
      return;
    }
    onUserId(mode, segment, userId, LOGIN_FAILURE_USER_ID_EXTRA_TAG);
    onEvent(mode, segment, "users.login.failure", false, metadata);
    if (exists != null) {
      segment.setTagTop(LOGIN_FAILURE_NO_USER_TAG, exists, false);
    }
  }

  @Override
  public void onCustomEvent(
      final UserIdCollectionMode mode, final String eventName, final Map<String, String> metadata) {
    if (eventName == null || eventName.isEmpty()) {
      throw new IllegalArgumentException("EventName is null or empty");
    }
    TraceSegment segment = beforeEvent(mode);
    if (segment == null) {
      return;
    }
    onEvent(mode, segment, eventName, true, metadata);
  }

  /** Takes care of user anonymization if required. */
  protected void onUserId(
      @Nonnull final UserIdCollectionMode mode,
      final TraceSegment segment,
      final String userId,
      final String... extraTags) {
    if (mode == SDK || !hasSdkEvent(segment)) {
      String finalUserId = mode == ANONYMIZATION ? anonymize(userId) : userId;
      segment.setTagTop("usr.id", finalUserId);
      segment.setTagTop(USER_COLLECTION_MODE_TAG, mode.shortName());
      for (String tag : extraTags) {
        segment.setTagTop(tag, finalUserId, false);
      }
    }
  }

  private void onEvent(
      @Nonnull final UserIdCollectionMode mode,
      @Nonnull final TraceSegment segment,
      String eventName,
      boolean sanitize,
      Map<String, String> metadata) {
    segment.setTagTop("appsec.events." + eventName + ".track", true, sanitize);
    segment.setTagTop(Tags.ASM_KEEP, true);
    segment.setTagTop(Tags.PROPAGATED_APPSEC, true);
    if (mode == SDK) {
      segment.setTagTop("_dd.appsec.events." + eventName + ".sdk", true, sanitize);
    } else {
      segment.setTagTop("_dd.appsec.events." + eventName + ".auto.mode", mode.fullName(), sanitize);
    }
    if (metadata != null && !metadata.isEmpty()) {
      segment.setTagTop("appsec.events." + eventName, metadata, sanitize);
    }
  }

  protected TraceSegment beforeEvent(final UserIdCollectionMode mode, final String userId) {
    if (userId == null || userId.isEmpty()) {
      if (mode == SDK) {
        throw new IllegalArgumentException("UserId is null or empty");
      } else {
        // increment metric and ignore the event
        WafMetricCollector.get().missingUserId();
        return null;
      }
    }
    return beforeEvent(mode);
  }

  protected TraceSegment beforeEvent(final UserIdCollectionMode mode) {
    if (!isEnabled(mode)) {
      return null;
    }
    return getSegment();
  }

  protected boolean hasSdkEvent(final TraceSegment segment) {
    if (segment == null) {
      return false;
    }
    final Object currentMode = segment.getTagTop(USER_COLLECTION_MODE_TAG);
    return SDK.shortName().equals(currentMode);
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
    return ANON_PREFIX + toHexString(hash);
  }

  protected TraceSegment getSegment() {
    return AgentTracer.get().getTraceSegment();
  }
}
