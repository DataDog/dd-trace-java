package datadog.trace.core.serialization.msgpack;

public class Util {

  private static final byte[] DIGIT_TENS = {
    '0', '0', '0', '0', '0', '0', '0', '0', '0', '0',
    '1', '1', '1', '1', '1', '1', '1', '1', '1', '1',
    '2', '2', '2', '2', '2', '2', '2', '2', '2', '2',
    '3', '3', '3', '3', '3', '3', '3', '3', '3', '3',
    '4', '4', '4', '4', '4', '4', '4', '4', '4', '4',
    '5', '5', '5', '5', '5', '5', '5', '5', '5', '5',
    '6', '6', '6', '6', '6', '6', '6', '6', '6', '6',
    '7', '7', '7', '7', '7', '7', '7', '7', '7', '7',
    '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
    '9', '9', '9', '9', '9', '9', '9', '9', '9', '9',
  };

  private static final byte[] DIGIT_ONES = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
  };

  private final byte[] numberByteArray = new byte[20]; // this is max long digits and sign

  public static void writeLongAsString(
      final long value, final Writable destination, byte[] numberByteArray) {
    int pos = 20; // start from the end
    long l = value;
    boolean negative = (l < 0);
    if (!negative) {
      l = -l; // do the conversion on negative values to not overflow Long.MIN_VALUE
    }

    int r;
    // convert 2 digits per iteration with longs until quotient fits into an int
    long lq;
    while (l <= Integer.MIN_VALUE) {
      lq = l / 100;
      r = (int) ((lq * 100) - l);
      l = lq;
      numberByteArray[--pos] = DIGIT_ONES[r];
      numberByteArray[--pos] = DIGIT_TENS[r];
    }

    // convert 2 digits per iteration with ints
    int iq;
    int i = (int) l;
    while (i <= -100) {
      iq = i / 100;
      r = (iq * 100) - i;
      i = iq;
      numberByteArray[--pos] = DIGIT_ONES[r];
      numberByteArray[--pos] = DIGIT_TENS[r];
    }

    // now there are at most two digits left
    iq = i / 10;
    r = (iq * 10) - i;
    numberByteArray[--pos] = (byte) ('0' + r);

    // if there is something left it is the remaining digit
    if (iq < 0) {
      numberByteArray[--pos] = (byte) ('0' - iq);
    }

    if (negative) {
      numberByteArray[--pos] = (byte) '-';
    }

    int len = 20 - pos;
    destination.writeUTF8(numberByteArray, pos, len);
  }
}
