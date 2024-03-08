package foo.bar;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.function.BiFunction;
import java.util.function.Function;

public class TestLambdaMetaFactorySuite {

  public static byte[] getBytes(final String value) {
    return function(value, String::getBytes);
  }

  public static String concat(final String first, final String last) {
    return biFunction(first, last, String::concat);
  }

  public static String decode(final byte[] value, int offset, int length, String charset)
      throws UnsupportedEncodingException {
    return decode(value, offset, length, charset, String::new);
  }

  public static String altMetaFactory(final String first, final String last) {
    final Concat concat = new Concat();
    return concat(first, last, concat::concat);
  }

  private static byte[] function(final String value, final Function<String, byte[]> fn) {
    return fn.apply(value);
  }

  private static String biFunction(
      final String first, final String last, final BiFunction<String, String, String> fn) {
    return fn.apply(first, last);
  }

  private static String decode(
      final byte[] value, int offset, int length, String charset, final Decoder decoder)
      throws UnsupportedEncodingException {
    return decoder.decode(value, offset, length, charset);
  }

  private static String concat(
      final String first, final String last, final IConcat<String, String> fn) {
    return fn.call(first, last);
  }

  @FunctionalInterface
  public interface Decoder {
    String decode(byte[] value, int offset, int length, String charset)
        throws UnsupportedEncodingException;
  }

  abstract static class AbstractConcat implements Serializable {
    String concat(String first, String last) {
      return doConcat(first, last);
    }

    abstract String doConcat(final String first, final String last);
  }

  public static class Concat extends AbstractConcat {

    @Override
    String doConcat(final String first, final String last) {
      return first.concat(last);
    }
  }

  interface IConcat<P, R> extends Serializable {
    R call(P first, P last);
  }
}
