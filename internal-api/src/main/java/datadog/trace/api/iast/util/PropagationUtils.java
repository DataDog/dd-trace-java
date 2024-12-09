package datadog.trace.api.iast.util;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.propagation.StringModule;
import java.net.URI;

public abstract class PropagationUtils {

  private PropagationUtils() {}

  public static URI onUriCreate(final String value, final URI uri) {
    final CodecModule module = InstrumentationBridge.CODEC;
    if (module != null) {
      try {
        module.onUriCreate(uri, value);
      } catch (final Throwable e) {
        module.onUnexpectedException("onUriCreate threw", e);
      }
    }
    return uri;
  }

  public static String onStringBuilderToString(final StringBuilder sb, final String result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringBuilderToString(sb, result);
      } catch (final Throwable e) {
        module.onUnexpectedException("onStringBuilderToString threw", e);
      }
    }
    return result;
  }

  public static StringBuilder onStringBuilderAppend(final String path, final StringBuilder sb) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringBuilderAppend(sb, path);
      } catch (final Throwable e) {
        module.onUnexpectedException("onStringBuilderAppend threw", e);
      }
    }
    return sb;
  }

  public static void taintObjectIfTainted(final Object target, final Object input) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintObjectIfTainted(target, input);
      } catch (final Throwable e) {
        module.onUnexpectedException("taintObjectIfTainted threw", e);
      }
    }
  }
}
