package datadog.trace.instrumentation.robolectric;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.versioning.AndroidVersions;

public final class RobolectricTestAnnotator {

  /** Matches the version in a {@code robolectric-<version>.jar} file name. */
  private static final Pattern ROBOLECTRIC_JAR = Pattern.compile("^robolectric-(.+)\\.jar$");

  private RobolectricTestAnnotator() {}

  public static void annotate() {
    int apiLevel = RuntimeEnvironment.getApiLevel();
    if (apiLevel <= 0) {
      return;
    }

    AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return;
    }
    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null
        || requestContext.getData(RequestContextSlot.CI_VISIBILITY) == null) {
      // The active span is not a CI Visibility test span; nothing to enrich.
      return;
    }

    span.setTag(Tags.TEST_ANDROID_API_LEVEL, apiLevel);
    AndroidVersions.AndroidRelease release = AndroidVersions.getReleaseForSdkInt(apiLevel);
    if (release != null) {
      span.setTag(Tags.TEST_ANDROID_RELEASE, release.getVersion());
      span.setTag(Tags.TEST_ANDROID_CODENAME, release.getShortCode());
    }
    String robolectricVersion = robolectricVersion();
    if (robolectricVersion != null) {
      span.setTag(Tags.TEST_ANDROID_ROBOLECTRIC_VERSION, robolectricVersion);
    }
  }

  private static String robolectricVersion() {
    try {
      // RuntimeEnvironment is re-loaded by the sandbox classloader with no CodeSource, but the
      // runner runs outside the sandbox (it creates it), so it is delegated to the application
      // classloader and its CodeSource points at the real robolectric-<version>.jar.
      ProtectionDomain protectionDomain = RobolectricTestRunner.class.getProtectionDomain();
      CodeSource codeSource = protectionDomain != null ? protectionDomain.getCodeSource() : null;
      URL location = codeSource != null ? codeSource.getLocation() : null;
      if (location == null) {
        return null;
      }
      Matcher matcher = ROBOLECTRIC_JAR.matcher(new File(location.getPath()).getName());
      return matcher.matches() ? matcher.group(1) : null;
    } catch (Throwable t) {
      return null;
    }
  }
}
