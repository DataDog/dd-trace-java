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
import javax.annotation.Nullable;

@Propagation
@CallSite(spi = IastCallSites.class)
public class URICallSite {

  @CallSite.After("java.net.URI java.net.URI.create(java.lang.String)")
  public static URI afterCreate(
      @CallSite.Argument @Nullable final String value, @CallSite.Return @Nonnull final URI result) {
    if (value != null) {
      final CodecModule module = InstrumentationBridge.CODEC;
      if (module != null) {
        try {
          module.onUriCreate(result, value);
        } catch (final Throwable e) {
          module.onUnexpectedException("create threw", e);
        }
      }
    }
    return result;
  }

  @CallSite.After("void java.net.URI.<init>(java.lang.String)")
  @CallSite.After(
      "void java.net.URI.<init>(java.lang.String, java.lang.String, java.lang.String, int, java.lang.String, java.lang.String, java.lang.String)")
  @CallSite.After(
      "void java.net.URI.<init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)")
  @CallSite.After(
      "void java.net.URI.<init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String)")
  @CallSite.After("void java.net.URI.<init>(java.lang.String, java.lang.String, java.lang.String)")
  public static URI afterCtor(
      @CallSite.AllArguments final Object[] args, @CallSite.Return @Nonnull final URI result) {
    if (args != null && args.length > 0) {
      final CodecModule module = InstrumentationBridge.CODEC;
      if (module != null) {
        try {
          module.onUriCreate(result, args);
        } catch (final Throwable e) {
          module.onUnexpectedException("ctor threw", e);
        }
      }
    }
    return result;
  }

  /**
   * Internally the URI is tainted following the <code>toString</code> representation
   *
   * @see CodecModule#onUriCreate(URI, Object...)
   */
  @CallSite.After("java.lang.String java.net.URI.toString()")
  public static String afterToString(
      @CallSite.This final URI url, @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, url, true, NOT_MARKED);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toString threw", e);
      }
    }
    return result;
  }

  /**
   * @see #afterToString(URI, String)
   */
  @CallSite.After("java.lang.String java.net.URI.toASCIIString()")
  public static String afterToASCIIString(
      @CallSite.This final URI url, @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null && result != null) {
      try {
        boolean keepRanges = url.toString().equals(result);
        module.taintStringIfTainted(result, url, keepRanges, NOT_MARKED);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toASCIIString threw", e);
      }
    }
    return result;
  }

  /**
   * @see #afterToString(URI, String)
   */
  @CallSite.After("java.net.URI java.net.URI.normalize()")
  public static URI afterNormalize(
      @CallSite.This final URI url, @CallSite.Return final URI result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null && result != null) {
      try {
        boolean keepRanges = url.toString().equals(result.toString());
        module.taintObjectIfTainted(result, url, keepRanges, NOT_MARKED);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toString threw", e);
      }
    }
    return result;
  }

  @Propagation
  @CallSite.After("java.net.URL java.net.URI.toURL()")
  public static URL afterToURL(@CallSite.This final URI uri, @CallSite.Return final URL result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null && result != null) {
      try {
        boolean keepRanges = uri.toString().equals(result.toString());
        module.taintObjectIfTainted(result, uri, keepRanges, NOT_MARKED);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toURL threw", e);
      }
    }
    return result;
  }
}
