package datadog.trace.bootstrap.jit;

import java.text.DecimalFormatSymbols;
import java.util.Formattable;
import java.util.Formatter;
import java.util.Locale;

/**
 * A {@link String#format} format string compiled into literal text and argument conversions.
 *
 * <p>Only literal text, {@code %s}, {@code %d}, {@code %n} and {@code %%} are supported, with no
 * argument index, flags, width or precision; {@link #parse} returns {@code null} for anything else.
 * {@code %n} and {@code %%} are folded into the literal text.
 *
 * <p>{@link #apply} reproduces {@link Formatter}: {@code %s} prints {@code "null"} or the
 * argument's {@code toString()}, and {@code %d} prints a {@link Byte}, {@link Short}, {@link
 * Integer} or {@link Long} in decimal, with a {@code '-'} sign (never localized) and the locale's
 * zero digit. It returns {@code null}, before calling any {@code toString()}, when the arguments
 * need behavior it doesn't reproduce: too few or {@code null} arguments, a {@link Formattable} for
 * {@code %s}, or another type for {@code %d}. The caller then uses the JDK method. (If a {@code
 * toString()} changes a later argument in the array, the JDK formats the rest of the call.)
 *
 * <p>Instances are immutable, so they can be shared between threads without synchronization.
 */
final class CompiledFormat {
  /** Conversion kinds. */
  private static final byte STRING = 0;

  private static final byte DECIMAL = 1;

  /** The format string this was compiled from. */
  final String source;

  /** Literal text around the conversions; one more entry than {@link #kinds}. */
  private final String[] literals;

  /** The conversion of each argument, in order. */
  private final byte[] kinds;

  /** Where each conversion starts in {@link #source}. */
  private final int[] starts;

  /** Total length of the literal text, for sizing the result. */
  private final int literalLength;

  private CompiledFormat(
      String source, String[] literals, byte[] kinds, int[] starts, int literalLength) {
    this.source = source;
    this.literals = literals;
    this.kinds = kinds;
    this.starts = starts;
    this.literalLength = literalLength;
  }

  /** Compiles a format string, or returns {@code null} if it uses anything unsupported. */
  static CompiledFormat parse(String format) {
    if (null == format) {
      return null;
    }
    int length = format.length();
    // at most one conversion per two characters
    String[] literals = new String[length / 2 + 1];
    byte[] kinds = new byte[length / 2];
    int[] starts = new int[length / 2];
    int count = 0;
    int literalLength = 0;
    StringBuilder literal = new StringBuilder();
    int from = 0;
    int percent;
    while ((percent = format.indexOf('%', from)) >= 0) {
      if (percent + 1 >= length) {
        return null; // trailing '%'
      }
      literal.append(format, from, percent);
      char conversion = format.charAt(percent + 1);
      switch (conversion) {
        case 's':
        case 'd':
          literals[count] = literal.toString();
          literalLength += literal.length();
          literal.setLength(0);
          starts[count] = percent;
          kinds[count++] = conversion == 's' ? STRING : DECIMAL;
          break;
        case 'n':
          literal.append(System.lineSeparator());
          break;
        case '%':
          literal.append('%');
          break;
        default:
          return null; // flags, width, precision, index, or another conversion
      }
      from = percent + 2;
    }
    literal.append(format, from, length);
    literals[count] = literal.toString();
    literalLength += literal.length();
    String[] trimmedLiterals = new String[count + 1];
    System.arraycopy(literals, 0, trimmedLiterals, 0, count + 1);
    byte[] trimmedKinds = new byte[count];
    System.arraycopy(kinds, 0, trimmedKinds, 0, count);
    int[] trimmedStarts = new int[count];
    System.arraycopy(starts, 0, trimmedStarts, 0, count);
    return new CompiledFormat(format, trimmedLiterals, trimmedKinds, trimmedStarts, literalLength);
  }

  /** Whether this was compiled from the given format string. */
  boolean isFor(String format) {
    return source == format || source.equals(format);
  }

  /**
   * Formats like {@code String.format(locale, source, args)}, or returns {@code null} without any
   * side effects if the arguments need the JDK.
   */
  String apply(Locale locale, Object[] args) {
    byte[] kinds = this.kinds;
    int count = kinds.length;
    if (null == args || args.length < count) {
      return null;
    }
    // check everything before calling any toString(), so falling back has no side effects
    char zero = 0;
    for (int i = 0; i < count; i++) {
      Object arg = args[i];
      if (kinds[i] == STRING) {
        if (arg instanceof Formattable) {
          return null;
        }
      } else if (null != arg) {
        if (!isFixedInteger(arg)) {
          return null;
        }
        if (zero == 0 && (zero = zeroDigit(locale)) == 0) {
          return null;
        }
      }
    }
    String[] literals = this.literals;
    StringBuilder sb = new StringBuilder(literalLength + (count << 4));
    sb.append(literals[0]);
    for (int i = 0; i < count; i++) {
      // read the argument again, exactly when the JDK does: a toString() may change the array
      Object arg = args[i];
      if (kinds[i] == STRING) {
        if (arg instanceof Formattable) {
          return finishWithFormatter(sb, locale, args, i);
        }
        sb.append(null == arg ? null : arg.toString()); // a null String appends "null"
      } else if (null == arg) {
        sb.append("null");
      } else if (zero != 0 && isFixedInteger(arg)) {
        appendDecimal(sb, ((Number) arg).longValue(), zero);
      } else {
        return finishWithFormatter(sb, locale, args, i);
      }
      sb.append(literals[i + 1]);
    }
    return sb.toString();
  }

  private static boolean isFixedInteger(Object arg) {
    return arg instanceof Integer
        || arg instanceof Long
        || arg instanceof Short
        || arg instanceof Byte;
  }

  /**
   * Appends like {@code Formatter}'s {@code %d}: '-' then the digits, shifted to the zero digit.
   */
  private static void appendDecimal(StringBuilder sb, long value, char zero) {
    if (zero == '0') {
      sb.append(value);
      return;
    }
    appendLocalizedDecimal(sb, value, zero);
  }

  private static void appendLocalizedDecimal(StringBuilder sb, long value, char zero) {
    String digits = Long.toString(value);
    int i = 0;
    if (value < 0) {
      sb.append('-');
      i = 1;
    }
    for (int length = digits.length(); i < length; i++) {
      sb.append((char) (digits.charAt(i) - '0' + zero));
    }
  }

  /**
   * Formats conversion {@code first} and everything after it with a JDK {@link Formatter} that
   * appends to the output so far. Only used when an argument changed after it was checked, which
   * needs an earlier {@code toString()} to change the array: the earlier {@code toString()} calls
   * can't be undone, so the JDK continues where it would have been. The rest of the format is kept
   * as written, except that its {@code %s} and {@code %d} get explicit indexes, so the JDK reads
   * the same array slots at the same time.
   */
  private String finishWithFormatter(StringBuilder sb, Locale locale, Object[] args, int first) {
    String source = this.source;
    int length = source.length();
    StringBuilder rest = new StringBuilder(length + 8);
    int index = first;
    for (int i = starts[first]; i < length; i++) {
      char c = source.charAt(i);
      rest.append(c);
      if (c == '%') {
        char conversion = source.charAt(++i);
        if (conversion == 's' || conversion == 'd') {
          rest.append(++index).append('$');
        }
        rest.append(conversion);
      }
    }
    return new Formatter(sb, locale).format(rest.toString(), args).toString();
  }

  /** The last locale whose zero digit was looked up; replaced, never mutated. */
  private static ZeroDigit lastZeroDigit = new ZeroDigit(null, '0');

  private static final class ZeroDigit {
    final Locale locale;
    final char zero;

    ZeroDigit(Locale locale, char zero) {
      this.locale = locale;
      this.zero = zero;
    }
  }

  /**
   * The zero digit {@code Formatter} uses for this locale, or {@code 0} if it can't be predicted.
   */
  static char zeroDigit(Locale locale) {
    if (null == locale) {
      return '0';
    }
    ZeroDigit last = lastZeroDigit;
    if (last.locale == locale) {
      return last.zero;
    }
    return lookUpZeroDigit(locale);
  }

  private static char lookUpZeroDigit(Locale locale) {
    char zero = DecimalFormatSymbols.getInstance(locale).getZeroDigit();
    // Up to JDK 17, Formatter uses '0' for Locale.US without asking DecimalFormatSymbols; later
    // versions always ask. They only disagree if a locale provider gives US another zero digit.
    if (zero != '0' && locale.equals(Locale.US)) {
      zero = 0;
    }
    lastZeroDigit = new ZeroDigit(locale, zero);
    return zero;
  }
}
