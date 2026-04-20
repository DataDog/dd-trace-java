package datadog.trace.instrumentation.owasp.esapi;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.VulnerabilityMarks;
import datadog.trace.api.iast.propagation.PropagationModule;
import javax.annotation.Nonnull;
import org.owasp.esapi.Encoder;
import org.owasp.esapi.codecs.Codec;

@Propagation
@CallSite(spi = IastCallSites.class)
public class EncoderCallSite {

  @CallSite.After("java.lang.String org.owasp.esapi.Encoder.encodeForHTML(java.lang.String)")
  public static String afterEncodeForHTML(
      @CallSite.This final Encoder encoder,
      @CallSite.Argument(0) @Nonnull final String input,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, input, false, VulnerabilityMarks.XSS_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEncodeForHTML threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String org.owasp.esapi.Encoder.canonicalize(java.lang.String)")
  public static String afterCanonicalize1(
      @CallSite.This final Encoder encoder,
      @CallSite.Argument(0) @Nonnull final String input,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, input, false, VulnerabilityMarks.XSS_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterCanonicalize1 threw", e);
      }
    }
    return result;
  }

  @CallSite.After(
      "java.lang.String org.owasp.esapi.Encoder.canonicalize(java.lang.String, boolean)")
  public static String afterCanonicalize2(
      @CallSite.This final Encoder encoder,
      @CallSite.Argument(0) @Nonnull final String input,
      @CallSite.Argument(1) final boolean strict,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, input, false, VulnerabilityMarks.XSS_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterCanonicalize2 threw", e);
      }
    }
    return result;
  }

  @CallSite.After(
      "java.lang.String org.owasp.esapi.Encoder.canonicalize(java.lang.String, boolean, boolean)")
  public static String afterCanonicalize3(
      @CallSite.This final Encoder encoder,
      @CallSite.Argument(0) @Nonnull final String input,
      @CallSite.Argument(1) final boolean strict,
      @CallSite.Argument(2) final boolean restrictMixed,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, input, false, VulnerabilityMarks.XSS_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterCanonicalize3 threw", e);
      }
    }
    return result;
  }

  @CallSite.After("java.lang.String org.owasp.esapi.Encoder.encodeForLDAP(java.lang.String)")
  public static String afterEncodeForLDAP(
      @CallSite.This final Encoder encoder,
      @CallSite.Argument(0) @Nonnull final String input,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, input, false, VulnerabilityMarks.LDAP_INJECTION_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEncodeForLDAP threw", e);
      }
    }
    return result;
  }

  @CallSite.After(
      "java.lang.String org.owasp.esapi.Encoder.encodeForOS(org.owasp.esapi.codecs.Codec, java.lang.String)")
  public static String afterEncodeForOS(
      @CallSite.This final Encoder encoder,
      @CallSite.Argument(0) @Nonnull final Codec codec,
      @CallSite.Argument(1) @Nonnull final String input,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(
            result, input, false, VulnerabilityMarks.COMMAND_INJECTION_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEncodeForOS threw", e);
      }
    }
    return result;
  }

  @CallSite.After(
      "java.lang.String org.owasp.esapi.Encoder.encodeForSQL(org.owasp.esapi.codecs.Codec, java.lang.String)")
  public static String afterEncodeForSQL(
      @CallSite.This final Encoder encoder,
      @CallSite.Argument(0) @Nonnull final Codec codec,
      @CallSite.Argument(1) @Nonnull final String input,
      @CallSite.Return final String result) {
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      try {
        module.taintStringIfTainted(result, input, false, VulnerabilityMarks.SQL_INJECTION_MARK);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterEncodeForSQL threw", e);
      }
    }
    return result;
  }
}
