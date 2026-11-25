package datadog.trace.bootstrap.jit;

import java.util.Locale;

/**
 * Drop-in replacements for {@link String#format} and {@code String.formatted}, substituted at their
 * call sites.
 *
 * <p>The slotted versions take the call site's {@link FormatSite} table and slot, so each site
 * compiles its own format once it's warm (see {@link FormatSite}). The unslotted versions share a
 * bounded global cache (see {@link FormatCache}). Compiled formats only handle the arguments they
 * reproduce exactly (see {@link CompiledFormat}); everything else, including {@code null} and
 * invalid formats, is delegated to the JDK method, which then throws its own exceptions. The only
 * visible difference is one extra stack frame.
 */
public final class FormatRewrites {
  private FormatRewrites() {}

  /** {@link String#format(String, Object...)} at the call site owning {@code sites[slot]}. */
  public static String format(FormatSite[] sites, int slot, String format, Object[] args) {
    CompiledFormat compiled = FormatSite.compiledFor(sites, slot, format);
    if (null != compiled) {
      String result = compiled.apply(Locale.getDefault(Locale.Category.FORMAT), args);
      if (null != result) {
        return result;
      }
    }
    return String.format(format, args);
  }

  /**
   * {@link String#format(Locale, String, Object...)} at the call site owning {@code sites[slot]}.
   */
  public static String format(
      FormatSite[] sites, int slot, Locale locale, String format, Object[] args) {
    CompiledFormat compiled = FormatSite.compiledFor(sites, slot, format);
    if (null != compiled) {
      String result = compiled.apply(locale, args);
      if (null != result) {
        return result;
      }
    }
    return String.format(locale, format, args);
  }

  /** {@link String#format(String, Object...)}, for a call site without a slot. */
  public static String format(String format, Object[] args) {
    CompiledFormat compiled = FormatCache.get(format);
    if (null != compiled) {
      String result = compiled.apply(Locale.getDefault(Locale.Category.FORMAT), args);
      if (null != result) {
        return result;
      }
    }
    return String.format(format, args);
  }

  /** {@link String#format(Locale, String, Object...)}, for a call site without a slot. */
  public static String format(Locale locale, String format, Object[] args) {
    CompiledFormat compiled = FormatCache.get(format);
    if (null != compiled) {
      String result = compiled.apply(locale, args);
      if (null != result) {
        return result;
      }
    }
    return String.format(locale, format, args);
  }

  /**
   * {@code format.formatted(args)} (JDK 15+), which is specified as {@code String.format(format,
   * args)}. Two differences remain: exceptions from the JDK show a {@code String.format} frame
   * instead of {@code String.formatted}, and for a {@code null} format the {@link
   * NullPointerException} is thrown inside the JDK instead of at the call site, so its helpful
   * message (which describes the caller's code) differs.
   */
  public static String formatted(String format, Object[] args) {
    return format(format, args);
  }
}
