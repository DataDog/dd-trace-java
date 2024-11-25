package foo.bar;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestStringSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestStringSuite.class);

  public static String concat(final String left, final String right) {
    LOGGER.debug("Before string concat {} {}", left, right);
    final String result = left.concat(right);
    LOGGER.debug("After string concat {}", result);
    return result;
  }

  public static String substring(String self, int beginIndex, int endIndex) {
    LOGGER.debug("Before string substring {} from {} to {}", self, beginIndex, endIndex);
    final String result = self.substring(beginIndex, endIndex);
    LOGGER.debug("After string substring {}", result);
    return result;
  }

  public static String substring(String self, int beginIndex) {
    LOGGER.debug("Before string substring {} from {}", self, beginIndex);
    final String result = self.substring(beginIndex);
    LOGGER.debug("After string substring {}", result);
    return result;
  }

  public static CharSequence subSequence(String self, Integer beginIndex, Integer endIndex) {
    LOGGER.debug("Before string subSequence {} from {} to {}", self, beginIndex, endIndex);
    final CharSequence result = self.subSequence(beginIndex, endIndex);
    LOGGER.debug("After string subSequence {}", result);
    return result;
  }

  public static String join(CharSequence delimiter, CharSequence[] elements) {
    LOGGER.debug("Before string join {} with {}", elements, delimiter);
    final String result = String.join(delimiter, elements);
    LOGGER.debug("After string join {}", result);
    return result;
  }

  public static String join(CharSequence delimiter, Iterable<? extends CharSequence> elements) {
    LOGGER.debug("Before string join {} with {}", elements, delimiter);
    final String result = String.join(delimiter, elements);
    LOGGER.debug("After string join {}", result);
    return result;
  }

  public static String stringToUpperCase(String in, Locale locale) {
    LOGGER.debug("Before string toUppercase {} ", in);
    if (null == locale) {
      final String result = in.toUpperCase();
      LOGGER.debug("After string toUppercase {}", result);
      return result;
    } else {
      final String result = in.toUpperCase(locale);
      LOGGER.debug("After string toUppercase {}", result);
      return result;
    }
  }

  public static String stringToLowerCase(String in, Locale locale) {
    LOGGER.debug("Before string toLowercase {} ", in);
    if (null == locale) {
      final String result = in.toLowerCase();
      LOGGER.debug("After string toLowercase {}", result);
      return result;
    } else {
      final String result = in.toLowerCase(locale);
      LOGGER.debug("After string toLowercase {}", result);
      return result;
    }
  }

  public static String stringTrim(final String self) {
    LOGGER.debug("Before string trim {} ", self);
    final String result = self.trim();
    LOGGER.debug("After string trim {}", result);
    return result;
  }

  public static String stringConstructor(String self) {
    LOGGER.debug("Before string stringConstructor {}", self);
    final String result = new String(self);
    LOGGER.debug("After string stringConstructor {}", result);
    return result;
  }

  public static String stringConstructor(StringBuffer self) {
    LOGGER.debug("Before string stringConstructor {}", self);
    final String result = new String(self);
    LOGGER.debug("After string stringConstructor {}", result);
    return result;
  }

  public static String stringConstructor(StringBuilder self) {
    LOGGER.debug("Before string stringConstructor {}", self);
    final String result = new String(self);
    LOGGER.debug("After string stringConstructor {}", result);
    return result;
  }

  public static String stringConstructor(final byte[] value) {
    LOGGER.debug("Before string stringConstructor {}", value);
    final String result = new String(value);
    LOGGER.debug("After string stringConstructor {}", result);
    return result;
  }

  public static String stringConstructor(final byte[] value, final int offset, final int length) {
    LOGGER.debug("Before string stringConstructor {} {} {}", value, offset, length);
    final String result = new String(value, offset, length);
    LOGGER.debug("After string stringConstructor {}", result);
    return result;
  }

  public static String stringConstructor(final byte[] value, final String charset)
      throws UnsupportedEncodingException {
    LOGGER.debug("Before string stringConstructor {} {}", value, charset);
    final String result = new String(value, charset);
    LOGGER.debug("After string stringConstructor {}", result);
    return result;
  }

  public static String stringConstructor(
      final byte[] value, final int offset, final int length, final String charset)
      throws UnsupportedEncodingException {
    LOGGER.debug("Before string stringConstructor {} {} {} {}", value, offset, length, charset);
    final String result = new String(value, offset, length, charset);
    LOGGER.debug("After string stringConstructor {}", result);
    return result;
  }

  public static String stringConstructor(final byte[] value, final Charset charset) {
    LOGGER.debug("Before string stringConstructor {} {}", value, charset);
    final String result = new String(value, charset);
    LOGGER.debug("After string stringConstructor {}", result);
    return result;
  }

  public static String stringConstructor(
      final byte[] value, final int offset, final int length, final Charset charset) {
    LOGGER.debug("Before string stringConstructor {} {} {} {}", value, offset, length, charset);
    final String result = new String(value, offset, length, charset);
    LOGGER.debug("After string stringConstructor {}", result);
    return result;
  }

  public static byte[] getBytes(final String self) {
    LOGGER.debug("Before string getBytes {}", self);
    final byte[] result = self.getBytes();
    LOGGER.debug("After string getBytes {}", result);
    return result;
  }

  public static byte[] getBytes(final String self, final String charset)
      throws UnsupportedEncodingException {
    LOGGER.debug("Before string getBytes {} {}", self, charset);
    final byte[] result = self.getBytes(charset);
    LOGGER.debug("After string getBytes {}", result);
    return result;
  }

  public static byte[] getBytes(final String self, final Charset charset) {
    LOGGER.debug("Before string getBytes {} {}", self, charset);
    final byte[] result = self.getBytes(charset);
    LOGGER.debug("After string getBytes {}", result);
    return result;
  }

  public static String format(final String pattern, final Object... args) {
    LOGGER.debug("Before format {} {}", pattern, args);
    final String result = String.format(pattern, args);
    LOGGER.debug("After format {}", result);
    return result;
  }

  public static String format(final Locale locale, final String pattern, final Object... args) {
    LOGGER.debug("Before format {} {} {}", locale, pattern, args);
    final String result = String.format(locale, pattern, args);
    LOGGER.debug("After format {}", result);
    return result;
  }

  public static char[] toCharArray(final String string) {
    LOGGER.debug("Before toCharArray {}", string);
    char[] result = string.toCharArray();
    LOGGER.debug("After toCharArray {}", result);
    return result;
  }

  public static String[] split(final String string, final String regex) {
    LOGGER.debug("Before split {} {}", string, regex);
    String[] result = string.split(regex);
    LOGGER.debug("After split {}", result);
    return result;
  }

  public static String[] split(final String string, final String regex, final int limit) {
    LOGGER.debug("Before split {} {} {}", string, regex, limit);
    String[] result = string.split(regex, limit);
    LOGGER.debug("After split {}", result);
    return result;
  }

  public static String replace(final String string, final char oldChar, final char newChar) {
    LOGGER.debug("Before replace {} {} {}", string, oldChar, newChar);
    String result = string.replace(oldChar, newChar);
    LOGGER.debug("After replace {}", result);
    return result;
  }

  public static String replace(
      final String string, final CharSequence oldCharSeq, final CharSequence newCharSeq) {
    LOGGER.debug("Before replace {} {} {}", string, oldCharSeq, newCharSeq);
    String result = string.replace(oldCharSeq, newCharSeq);
    LOGGER.debug("After replace {}", result);
    return result;
  }

  public static String replaceAll(
      final String string, final String regex, final String replacement) {
    LOGGER.debug("Before replace all {} {} {}", string, regex, replacement);
    String result = string.replaceAll(regex, replacement);
    LOGGER.debug("After replace all {}", result);
    return result;
  }

  public static String replaceFirst(
      final String string, final String regex, final String replacement) {
    LOGGER.debug("Before replace first {} {} {}", string, regex, replacement);
    String result = string.replaceFirst(regex, replacement);
    LOGGER.debug("After replace first {}", result);
    return result;
  }

  public static String valueOf(final Object param) {
    LOGGER.debug("Before valueOf {}", param);
    String result = String.valueOf(param);
    LOGGER.debug("After valueOf {}", result);
    return result;
  }
}
