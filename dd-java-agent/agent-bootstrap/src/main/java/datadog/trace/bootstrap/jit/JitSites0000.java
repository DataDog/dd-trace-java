package datadog.trace.bootstrap.jit;

import java.util.Locale;

/**
 * PROTOTYPE: a chunk of per-call-site trampolines, one slot per rewritten call site.
 *
 * <p>Each trampoline passes this chunk's own {@link #SITES} array and its slot number to a generic
 * handler, so per-site state costs one array load once the trampoline is inlined. When the slots
 * run out, the rewriter defines a copy of this class with a new name (same length, so only the
 * class name constant changes); each copy has its own {@code SITES}.
 *
 * <p>Generated: {@value #SLOTS} slots; don't edit the trampolines by hand.
 */
public final class JitSites0000 {
  public static final int SLOTS = 64;

  static final FormatSite[] SITES = new FormatSite[SLOTS];

  private JitSites0000() {}

  public static String format$0(String format, Object[] args) {
    return FormatRewrites.format(SITES, 0, format, args);
  }

  public static String formatLocale$0(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 0, locale, format, args);
  }

  public static String format$1(String format, Object[] args) {
    return FormatRewrites.format(SITES, 1, format, args);
  }

  public static String formatLocale$1(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 1, locale, format, args);
  }

  public static String format$2(String format, Object[] args) {
    return FormatRewrites.format(SITES, 2, format, args);
  }

  public static String formatLocale$2(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 2, locale, format, args);
  }

  public static String format$3(String format, Object[] args) {
    return FormatRewrites.format(SITES, 3, format, args);
  }

  public static String formatLocale$3(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 3, locale, format, args);
  }

  public static String format$4(String format, Object[] args) {
    return FormatRewrites.format(SITES, 4, format, args);
  }

  public static String formatLocale$4(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 4, locale, format, args);
  }

  public static String format$5(String format, Object[] args) {
    return FormatRewrites.format(SITES, 5, format, args);
  }

  public static String formatLocale$5(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 5, locale, format, args);
  }

  public static String format$6(String format, Object[] args) {
    return FormatRewrites.format(SITES, 6, format, args);
  }

  public static String formatLocale$6(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 6, locale, format, args);
  }

  public static String format$7(String format, Object[] args) {
    return FormatRewrites.format(SITES, 7, format, args);
  }

  public static String formatLocale$7(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 7, locale, format, args);
  }

  public static String format$8(String format, Object[] args) {
    return FormatRewrites.format(SITES, 8, format, args);
  }

  public static String formatLocale$8(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 8, locale, format, args);
  }

  public static String format$9(String format, Object[] args) {
    return FormatRewrites.format(SITES, 9, format, args);
  }

  public static String formatLocale$9(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 9, locale, format, args);
  }

  public static String format$10(String format, Object[] args) {
    return FormatRewrites.format(SITES, 10, format, args);
  }

  public static String formatLocale$10(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 10, locale, format, args);
  }

  public static String format$11(String format, Object[] args) {
    return FormatRewrites.format(SITES, 11, format, args);
  }

  public static String formatLocale$11(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 11, locale, format, args);
  }

  public static String format$12(String format, Object[] args) {
    return FormatRewrites.format(SITES, 12, format, args);
  }

  public static String formatLocale$12(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 12, locale, format, args);
  }

  public static String format$13(String format, Object[] args) {
    return FormatRewrites.format(SITES, 13, format, args);
  }

  public static String formatLocale$13(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 13, locale, format, args);
  }

  public static String format$14(String format, Object[] args) {
    return FormatRewrites.format(SITES, 14, format, args);
  }

  public static String formatLocale$14(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 14, locale, format, args);
  }

  public static String format$15(String format, Object[] args) {
    return FormatRewrites.format(SITES, 15, format, args);
  }

  public static String formatLocale$15(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 15, locale, format, args);
  }

  public static String format$16(String format, Object[] args) {
    return FormatRewrites.format(SITES, 16, format, args);
  }

  public static String formatLocale$16(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 16, locale, format, args);
  }

  public static String format$17(String format, Object[] args) {
    return FormatRewrites.format(SITES, 17, format, args);
  }

  public static String formatLocale$17(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 17, locale, format, args);
  }

  public static String format$18(String format, Object[] args) {
    return FormatRewrites.format(SITES, 18, format, args);
  }

  public static String formatLocale$18(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 18, locale, format, args);
  }

  public static String format$19(String format, Object[] args) {
    return FormatRewrites.format(SITES, 19, format, args);
  }

  public static String formatLocale$19(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 19, locale, format, args);
  }

  public static String format$20(String format, Object[] args) {
    return FormatRewrites.format(SITES, 20, format, args);
  }

  public static String formatLocale$20(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 20, locale, format, args);
  }

  public static String format$21(String format, Object[] args) {
    return FormatRewrites.format(SITES, 21, format, args);
  }

  public static String formatLocale$21(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 21, locale, format, args);
  }

  public static String format$22(String format, Object[] args) {
    return FormatRewrites.format(SITES, 22, format, args);
  }

  public static String formatLocale$22(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 22, locale, format, args);
  }

  public static String format$23(String format, Object[] args) {
    return FormatRewrites.format(SITES, 23, format, args);
  }

  public static String formatLocale$23(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 23, locale, format, args);
  }

  public static String format$24(String format, Object[] args) {
    return FormatRewrites.format(SITES, 24, format, args);
  }

  public static String formatLocale$24(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 24, locale, format, args);
  }

  public static String format$25(String format, Object[] args) {
    return FormatRewrites.format(SITES, 25, format, args);
  }

  public static String formatLocale$25(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 25, locale, format, args);
  }

  public static String format$26(String format, Object[] args) {
    return FormatRewrites.format(SITES, 26, format, args);
  }

  public static String formatLocale$26(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 26, locale, format, args);
  }

  public static String format$27(String format, Object[] args) {
    return FormatRewrites.format(SITES, 27, format, args);
  }

  public static String formatLocale$27(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 27, locale, format, args);
  }

  public static String format$28(String format, Object[] args) {
    return FormatRewrites.format(SITES, 28, format, args);
  }

  public static String formatLocale$28(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 28, locale, format, args);
  }

  public static String format$29(String format, Object[] args) {
    return FormatRewrites.format(SITES, 29, format, args);
  }

  public static String formatLocale$29(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 29, locale, format, args);
  }

  public static String format$30(String format, Object[] args) {
    return FormatRewrites.format(SITES, 30, format, args);
  }

  public static String formatLocale$30(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 30, locale, format, args);
  }

  public static String format$31(String format, Object[] args) {
    return FormatRewrites.format(SITES, 31, format, args);
  }

  public static String formatLocale$31(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 31, locale, format, args);
  }

  public static String format$32(String format, Object[] args) {
    return FormatRewrites.format(SITES, 32, format, args);
  }

  public static String formatLocale$32(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 32, locale, format, args);
  }

  public static String format$33(String format, Object[] args) {
    return FormatRewrites.format(SITES, 33, format, args);
  }

  public static String formatLocale$33(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 33, locale, format, args);
  }

  public static String format$34(String format, Object[] args) {
    return FormatRewrites.format(SITES, 34, format, args);
  }

  public static String formatLocale$34(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 34, locale, format, args);
  }

  public static String format$35(String format, Object[] args) {
    return FormatRewrites.format(SITES, 35, format, args);
  }

  public static String formatLocale$35(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 35, locale, format, args);
  }

  public static String format$36(String format, Object[] args) {
    return FormatRewrites.format(SITES, 36, format, args);
  }

  public static String formatLocale$36(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 36, locale, format, args);
  }

  public static String format$37(String format, Object[] args) {
    return FormatRewrites.format(SITES, 37, format, args);
  }

  public static String formatLocale$37(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 37, locale, format, args);
  }

  public static String format$38(String format, Object[] args) {
    return FormatRewrites.format(SITES, 38, format, args);
  }

  public static String formatLocale$38(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 38, locale, format, args);
  }

  public static String format$39(String format, Object[] args) {
    return FormatRewrites.format(SITES, 39, format, args);
  }

  public static String formatLocale$39(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 39, locale, format, args);
  }

  public static String format$40(String format, Object[] args) {
    return FormatRewrites.format(SITES, 40, format, args);
  }

  public static String formatLocale$40(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 40, locale, format, args);
  }

  public static String format$41(String format, Object[] args) {
    return FormatRewrites.format(SITES, 41, format, args);
  }

  public static String formatLocale$41(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 41, locale, format, args);
  }

  public static String format$42(String format, Object[] args) {
    return FormatRewrites.format(SITES, 42, format, args);
  }

  public static String formatLocale$42(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 42, locale, format, args);
  }

  public static String format$43(String format, Object[] args) {
    return FormatRewrites.format(SITES, 43, format, args);
  }

  public static String formatLocale$43(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 43, locale, format, args);
  }

  public static String format$44(String format, Object[] args) {
    return FormatRewrites.format(SITES, 44, format, args);
  }

  public static String formatLocale$44(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 44, locale, format, args);
  }

  public static String format$45(String format, Object[] args) {
    return FormatRewrites.format(SITES, 45, format, args);
  }

  public static String formatLocale$45(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 45, locale, format, args);
  }

  public static String format$46(String format, Object[] args) {
    return FormatRewrites.format(SITES, 46, format, args);
  }

  public static String formatLocale$46(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 46, locale, format, args);
  }

  public static String format$47(String format, Object[] args) {
    return FormatRewrites.format(SITES, 47, format, args);
  }

  public static String formatLocale$47(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 47, locale, format, args);
  }

  public static String format$48(String format, Object[] args) {
    return FormatRewrites.format(SITES, 48, format, args);
  }

  public static String formatLocale$48(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 48, locale, format, args);
  }

  public static String format$49(String format, Object[] args) {
    return FormatRewrites.format(SITES, 49, format, args);
  }

  public static String formatLocale$49(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 49, locale, format, args);
  }

  public static String format$50(String format, Object[] args) {
    return FormatRewrites.format(SITES, 50, format, args);
  }

  public static String formatLocale$50(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 50, locale, format, args);
  }

  public static String format$51(String format, Object[] args) {
    return FormatRewrites.format(SITES, 51, format, args);
  }

  public static String formatLocale$51(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 51, locale, format, args);
  }

  public static String format$52(String format, Object[] args) {
    return FormatRewrites.format(SITES, 52, format, args);
  }

  public static String formatLocale$52(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 52, locale, format, args);
  }

  public static String format$53(String format, Object[] args) {
    return FormatRewrites.format(SITES, 53, format, args);
  }

  public static String formatLocale$53(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 53, locale, format, args);
  }

  public static String format$54(String format, Object[] args) {
    return FormatRewrites.format(SITES, 54, format, args);
  }

  public static String formatLocale$54(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 54, locale, format, args);
  }

  public static String format$55(String format, Object[] args) {
    return FormatRewrites.format(SITES, 55, format, args);
  }

  public static String formatLocale$55(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 55, locale, format, args);
  }

  public static String format$56(String format, Object[] args) {
    return FormatRewrites.format(SITES, 56, format, args);
  }

  public static String formatLocale$56(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 56, locale, format, args);
  }

  public static String format$57(String format, Object[] args) {
    return FormatRewrites.format(SITES, 57, format, args);
  }

  public static String formatLocale$57(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 57, locale, format, args);
  }

  public static String format$58(String format, Object[] args) {
    return FormatRewrites.format(SITES, 58, format, args);
  }

  public static String formatLocale$58(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 58, locale, format, args);
  }

  public static String format$59(String format, Object[] args) {
    return FormatRewrites.format(SITES, 59, format, args);
  }

  public static String formatLocale$59(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 59, locale, format, args);
  }

  public static String format$60(String format, Object[] args) {
    return FormatRewrites.format(SITES, 60, format, args);
  }

  public static String formatLocale$60(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 60, locale, format, args);
  }

  public static String format$61(String format, Object[] args) {
    return FormatRewrites.format(SITES, 61, format, args);
  }

  public static String formatLocale$61(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 61, locale, format, args);
  }

  public static String format$62(String format, Object[] args) {
    return FormatRewrites.format(SITES, 62, format, args);
  }

  public static String formatLocale$62(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 62, locale, format, args);
  }

  public static String format$63(String format, Object[] args) {
    return FormatRewrites.format(SITES, 63, format, args);
  }

  public static String formatLocale$63(Locale locale, String format, Object[] args) {
    return FormatRewrites.format(SITES, 63, locale, format, args);
  }
}
