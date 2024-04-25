package datadog.trace.instrumentation.java.net;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.net.URI;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Propagation
@CallSite(spi = IastCallSites.class)
public class URICallSite {

  @CallSite.After("java.net.URI java.net.URI.create(java.lang.String)")
  public static URI afterCreate(
      @CallSite.Argument @Nullable final String value, @CallSite.Return @Nonnull final URI result) {
    if (value != null) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taintObjectIfTainted(result, value);
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
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taintObjectIfAnyTainted(result, args);
        } catch (final Throwable e) {
          module.onUnexpectedException("ctor threw", e);
        }
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String java.net.URI.toString()")
  @CallSite.After("java.lang.String java.net.URI.toASCIIString()")
  public static String afterToString(
      @CallSite.This final URI url, @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, url);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toString threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.net.URI java.net.URI.normalize()")
  public static URI afterNormalize(
      @CallSite.This final URI url, @CallSite.Return final URI result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintObjectIfTainted(result, url);
      } catch (final Throwable e) {
        module.onUnexpectedException("After toString threw", e);
      }
    }
    return result;
  }
}
