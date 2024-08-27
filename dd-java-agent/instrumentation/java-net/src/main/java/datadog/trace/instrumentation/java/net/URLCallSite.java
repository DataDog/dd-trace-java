package datadog.trace.instrumentation.java.net;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.CodecModule;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.net.URI;
import java.net.URL;
import javax.annotation.Nonnull;

@CallSite(spi = IastCallSites.class)
public class URLCallSite {

  @Propagation
  @CallSite.After("void java.net.URL.<init>(java.lang.String)")
  @CallSite.After(
      "void java.net.URL.<init>(java.lang.String, java.lang.String, int, java.lang.String)")
  @CallSite.After(
      "void java.net.URL.<init>(java.lang.String, java.lang.String, int, java.lang.String, java.net.URLStreamHandler)")
  @CallSite.After("void java.net.URL.<init>(java.lang.String, java.lang.String, java.lang.String)")
  @CallSite.After("void java.net.URL.<init>(java.net.URL, java.lang.String)")
  @CallSite.After(
      "void java.net.URL.<init>(java.net.URL, java.lang.String, java.net.URLStreamHandler)")
  public static URL afterCtor(
      @CallSite.AllArguments final Object[] args, @CallSite.Return @Nonnull final URL result) {
    if (args != null && args.length > 0) {
      final CodecModule module = InstrumentationBridge.CODEC;
      if (module != null) {
        try {
          module.onUrlCreate(result, args);
        } catch (final Throwable e) {
          module.onUnexpectedException("ctor threw", e);
        }
      }
    }
    return result;
  }

  /**
   * Internally the URL is tainted following the <code>toString</code> representation
   *
   * @see CodecModule#onUrlCreate(URL, Object...)
   */
  @Propagation
  @CallSite.After("java.lang.String java.net.URL.toString()")
  public static String afterToString(
      @CallSite.This final URL url, @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null && result != null) {
      try {
        module.taintStringIfTainted(result, url, true, NOT_MARKED);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toString threw", e);
      }
    }
    return result;
  }

  /** @see #afterToString(URL, String) */
  @Propagation
  @CallSite.After("java.lang.String java.net.URL.toExternalForm()")
  public static String afterToExternalForm(
      @CallSite.This final URL url, @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null && result != null) {
      try {
        boolean keepRanges = url.toString().equals(result);
        module.taintStringIfTainted(result, url, keepRanges, NOT_MARKED);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toExternalForm threw", e);
      }
    }
    return result;
  }

  /** @see #afterToString(URL, String) */
  @Propagation
  @CallSite.After("java.net.URI java.net.URL.toURI()")
  public static URI afterToURI(@CallSite.This final URL url, @CallSite.Return final URI result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null && result != null) {
      try {
        boolean keepRanges = url.toString().equals(result.toString());
        module.taintObjectIfTainted(result, url, keepRanges, NOT_MARKED);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toURI threw", e);
      }
    }
    return result;
  }
}
