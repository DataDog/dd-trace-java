package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

// public so TableTest can use the type converter declared below
public class ProductTraceSourceTest {

  @TableTest({
    "scenario    | init  | newProduct | expected",
    "unset + ASM | UNSET | ASM        | ASM     ",
    "ASM + DSM   | ASM   | DSM        | 6       "
  })
  void updateProduct(int init, int newProduct, int expected) {
    int result = ProductTraceSource.updateProduct(init, newProduct);

    assertEquals(expected, result);
  }

  @TableTest({
    "scenario             | value    | product  | expected",
    "ASM in ASM           | ASM      | ASM      | true    ",
    "DSM in ASM           | DSM      | ASM      | false   ",
    "AI_GUARD in AI_GUARD | AI_GUARD | AI_GUARD | true    ",
    "ASM in AI_GUARD      | ASM      | AI_GUARD | false   "
  })
  void isProductMarked(int value, int product, boolean expected) {
    boolean result = ProductTraceSource.isProductMarked(value, product);

    assertEquals(expected, result);
  }

  @TableTest({
    "scenario | value    | productA | productB | expected",
    "ASM      | ASM      | ASM      | AI_GUARD | true    ",
    "AI_GUARD | AI_GUARD | ASM      | AI_GUARD | true    ",
    "DSM      | DSM      | ASM      | AI_GUARD | false   ",
    "unset    | UNSET    | ASM      | AI_GUARD | false   "
  })
  void isProductMarkedWithTwoProducts(int value, int productA, int productB, boolean expected) {
    boolean result = ProductTraceSource.isProductMarked(value, productA, productB);

    assertEquals(expected, result);
  }

  @TableTest({
    "scenario | value    | expected",
    "unset    | UNSET    | '00'    ",
    "ASM      | ASM      | '02'    ",
    "AI_GUARD | AI_GUARD | '20'    "
  })
  void getBitfieldHex(int value, String expected) {
    String result = ProductTraceSource.getBitfieldHex(value);

    assertEquals(expected, result);
  }

  @TableTest({
    "scenario | hex  | expected",
    "zero     | '00' | UNSET   ",
    "null     |      | UNSET   ",
    "empty    | ''   | UNSET   ",
    "ASM      | '02' | ASM     ",
    "AI_GUARD | '20' | AI_GUARD"
  })
  void parseBitfieldHex(String hex, int expected) {
    int result = ProductTraceSource.parseBitfieldHex(hex);

    assertEquals(expected, result);
  }

  /** Resolves {@link ProductTraceSource} constant names, or decodes plain numbers. */
  @TypeConverter
  public static int toProduct(String value) {
    switch (value) {
      case "UNSET":
        return ProductTraceSource.UNSET;
      case "ASM":
        return ProductTraceSource.ASM;
      case "DSM":
        return ProductTraceSource.DSM;
      case "AI_GUARD":
        return ProductTraceSource.AI_GUARD;
      default:
        return Integer.decode(value);
    }
  }
}
