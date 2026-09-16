package datadog.trace.instrumentation.robolectric;

import android.os.Build;
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
    span.setTag(Tags.TEST_ANDROID_RELEASE, Build.VERSION.RELEASE);
    String androidCodename = androidCodename(apiLevel);
    if (androidCodename != null) {
      span.setTag(Tags.TEST_ANDROID_CODENAME, androidCodename);
    }
    String robolectricVersion = robolectricVersion();
    if (robolectricVersion != null) {
      span.setTag(Tags.TEST_ANDROID_ROBOLECTRIC_VERSION, robolectricVersion);
    }
  }

  private static String androidCodename(int apiLevel) {
    switch (apiLevel) {
      case 1:
        return "BASE";
      case 2:
        return "BASE_1_1";
      case 3:
        return "CUPCAKE";
      case 4:
        return "DONUT";
      case 5:
        return "ECLAIR";
      case 6:
        return "ECLAIR_0_1";
      case 7:
        return "ECLAIR_MR1";
      case 8:
        return "FROYO";
      case 9:
        return "GINGERBREAD";
      case 10:
        return "GINGERBREAD_MR1";
      case 11:
        return "HONEYCOMB";
      case 12:
        return "HONEYCOMB_MR1";
      case 13:
        return "HONEYCOMB_MR2";
      case 14:
        return "ICE_CREAM_SANDWICH";
      case 15:
        return "ICE_CREAM_SANDWICH_MR1";
      case 16:
        return "JELLY_BEAN";
      case 17:
        return "JELLY_BEAN_MR1";
      case 18:
        return "JELLY_BEAN_MR2";
      case 19:
        return "KITKAT";
      case 20:
        return "KITKAT_WATCH";
      case 21:
        return "LOLLIPOP";
      case 22:
        return "LOLLIPOP_MR1";
      case 23:
        return "M";
      case 24:
        return "N";
      case 25:
        return "N_MR1";
      case 26:
        return "O";
      case 27:
        return "O_MR1";
      case 28:
        return "P";
      case 29:
        return "Q";
      case 30:
        return "R";
      case 31:
        return "S";
      case 32:
        return "S_V2";
      case 33:
        return "TIRAMISU";
      case 34:
        return "UPSIDE_DOWN_CAKE";
      case 35:
        return "VANILLA_ICE_CREAM";
      case 36:
        return "BAKLAVA";
      case 37:
        return "CINNAMON_BUN";
      default:
        return null;
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
