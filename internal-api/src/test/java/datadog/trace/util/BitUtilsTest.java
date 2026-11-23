package datadog.trace.util;

import static datadog.trace.util.BitUtils.nextPowerOfTwo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class BitUtilsTest {

  @TableTest({
    "scenario                  | input      | expected  ",
    "smallest case             | 0          | 1         ",
    "already power of two      | 1          | 1         ",
    "already power of two      | 2          | 2         ",
    "already power of two      | 3          | 4         ",
    "already power of two      | 4          | 4         ",
    "already power of two      | 5          | 8         ",
    "already power of two      | 6          | 8         ",
    "already power of two      | 7          | 8         ",
    "already power of two      | 8          | 8         ",
    "already power of two      | 9          | 16        ",
    "already power of two      | 15         | 16        ",
    "already power of two      | 16         | 16        ",
    "already power of two      | 17         | 32        ",
    "already power of two      | 31         | 32        ",
    "already power of two      | 32         | 32        ",
    "already power of two      | 33         | 64        ",
    "already power of two      | 63         | 64        ",
    "already power of two      | 64         | 64        ",
    "already power of two      | 65         | 128       ",
    "already power of two      | 1000       | 1024      ",
    "already power of two      | 1023       | 1024      ",
    "already power of two      | 1024       | 1024      ",
    "already power of two      | 1025       | 2048      ",
    "already power of two      | 4096       | 4096      ",
    "already power of two      | 4097       | 8192      ",
    "negative input edge case  | -1         | 1         ",
    "largest safe power of two | 2147483647 | 1073741824"
  })
  void nextPowerOfTwoShouldReturnExpected(int input, int expected) {
    assertEquals(expected, nextPowerOfTwo(input));
  }
}
