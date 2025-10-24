package com.datadog.debugger.agent;

import static com.datadog.debugger.el.DSL.*;
import static com.datadog.debugger.el.DSL.and;
import static com.datadog.debugger.el.DSL.contains;
import static com.datadog.debugger.el.DSL.endsWith;
import static com.datadog.debugger.el.DSL.eq;
import static com.datadog.debugger.el.DSL.ge;
import static com.datadog.debugger.el.DSL.getMember;
import static com.datadog.debugger.el.DSL.gt;
import static com.datadog.debugger.el.DSL.index;
import static com.datadog.debugger.el.DSL.instanceOf;
import static com.datadog.debugger.el.DSL.le;
import static com.datadog.debugger.el.DSL.len;
import static com.datadog.debugger.el.DSL.lt;
import static com.datadog.debugger.el.DSL.matches;
import static com.datadog.debugger.el.DSL.or;
import static com.datadog.debugger.el.DSL.ref;
import static com.datadog.debugger.el.DSL.startsWith;
import static com.datadog.debugger.el.DSL.subString;
import static com.datadog.debugger.el.DSL.value;
import static com.datadog.debugger.el.DSL.when;
import static com.datadog.debugger.el.ValueType.BOOLEAN;
import static com.datadog.debugger.el.ValueType.BYTE;
import static com.datadog.debugger.el.ValueType.DOUBLE;
import static com.datadog.debugger.el.ValueType.FLOAT;
import static com.datadog.debugger.el.ValueType.INT;
import static com.datadog.debugger.el.ValueType.LONG;
import static com.datadog.debugger.el.ValueType.OBJECT;
import static com.datadog.debugger.el.ValueType.SHORT;
import static com.datadog.debugger.el.expressions.BooleanExpression.TRUE;
import static com.datadog.debugger.el.expressions.ComparisonOperator.EQ;
import static com.datadog.debugger.el.expressions.ComparisonOperator.GE;
import static com.datadog.debugger.el.expressions.ComparisonOperator.GT;
import static com.datadog.debugger.el.expressions.ComparisonOperator.INSTANCEOF;
import static com.datadog.debugger.el.expressions.ComparisonOperator.LE;
import static com.datadog.debugger.el.expressions.ComparisonOperator.LT;
import static datadog.trace.bootstrap.debugger.el.ValueReferences.ITERATOR_REF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.el.expressions.ComparisonExpression;
import com.datadog.debugger.el.expressions.ComparisonOperator;
import com.datadog.debugger.el.expressions.ValueExpression;
import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.util.TestSnapshotListener;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.util.List;
import java.util.stream.Stream;
import org.joor.Reflect;
import org.joor.ReflectException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class CapturedSnapshotConditionTest extends CapturingTestBase {

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("binaryExprs")
  public void binaryComparisons(
      BooleanExpression expr, String dslExpression, boolean expectedResult)
      throws IOException, URISyntaxException {
    doCondition08(expr, dslExpression, expectedResult);
  }

  public static Stream<Arguments> binaryExprs() {
    return Stream.of(
        Arguments.of(
            and(
                and(eq(ref("arg"), value("5")), eq(value(5), value(5))),
                eq(value(true), value(true))),
            "arg == '5' && 5 == 5 && true == true",
            true),
        Arguments.of(not(eq(ref("arg"), nullValue())), "arg != null", true),
        Arguments.of(
            and(gt(ref("arg"), value("4")), gt(value(5), value(4))), "arg > '4' && 5 > 4", true),
        Arguments.of(
            and(
                and(ge(ref("arg"), value("4")), ge(value(5), value(4))),
                and(ge(ref("arg"), value("5")), ge(value(5), value(5)))),
            "arg >= '4' && 5 >= 4 && arg >= '5' && 5 >= 5",
            true),
        Arguments.of(
            and(lt(ref("arg"), value("6")), lt(value(4), value(5))), "arg < '6' && 4 < 5", true),
        Arguments.of(
            and(
                and(le(ref("arg"), value("6")), le(value(4), value(5))),
                and(le(ref("arg"), value("5")), le(value(5), value(5)))),
            "arg <= '4' && 5 <= 4 && arg <= '5' && 5 <= 5",
            true),
        Arguments.of(
            or(eq(ref("arg"), value("4")), eq(ref("arg"), value("5"))),
            "arg == '4' || arg == '5'",
            true),
        // or(true, X): right side would NPE if evaluated; short-circuit must skip it
        Arguments.of(
            or(TRUE, eq(getMember(getMember(ref("nullTyped"), "fld"), "fld"), value("5"))),
            "true || nullTyped.fld.fld == '5'",
            true),
        // and(false, X): right side would AIOOBE if evaluated; short-circuit must skip it
        Arguments.of(
            and(BooleanExpression.FALSE, eq(subString(ref("arg"), 100, 1), value("a"))),
            "false && substring(arg, 100, 1) == 'a'",
            false));
  }

  @ParameterizedTest(name = "[{index}] {4}")
  @MethodSource("comparisonExprs")
  public void comparisonExpressions(
      ValueExpression<?> left,
      ValueExpression<?> right,
      ComparisonOperator operator,
      boolean expected,
      String dslExpression)
      throws IOException, URISyntaxException {
    ComparisonExpression expression = new ComparisonExpression(left, right, operator);
    doCondition06(expression, dslExpression, expected);
  }

  public static Stream<Arguments> comparisonExprs() {
    return Stream.of(
        Arguments.of(
            new BooleanValue(true, BOOLEAN),
            new BooleanValue(true, BOOLEAN),
            EQ,
            true,
            "true == true"),
        Arguments.of(
            new BooleanValue(false, BOOLEAN),
            new BooleanValue(false, BOOLEAN),
            EQ,
            true,
            "false == false"),
        Arguments.of(
            new BooleanValue(true, BOOLEAN),
            new BooleanValue(false, BOOLEAN),
            EQ,
            false,
            "true == false"),
        Arguments.of(
            new BooleanValue(false, BOOLEAN),
            new BooleanValue(true, BOOLEAN),
            EQ,
            false,
            "false == true"),
        Arguments.of(
            new NumericValue((byte) 1, BYTE), new NumericValue((byte) 1, BYTE), EQ, true, "1 == 1"),
        Arguments.of(
            new NumericValue((short) 1, SHORT),
            new NumericValue((short) 1, SHORT),
            EQ,
            true,
            "1 == 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1, INT), EQ, true, "1 == 1"),
        Arguments.of(new NumericValue(1L, LONG), new NumericValue(1L, LONG), EQ, true, "1 == 1"),
        Arguments.of(
            new NumericValue(1.0F, FLOAT), new NumericValue(1.0F, FLOAT), EQ, true, "1.0 == 1.0"),
        Arguments.of(
            new NumericValue(1.0, DOUBLE), new NumericValue(1.0, DOUBLE), EQ, true, "1.0 == 1.0"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1.0, DOUBLE), EQ, true, "1 == 1.0"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(2, INT), EQ, false, "1 == 2"),
        Arguments.of(
            new NumericValue(1, INT), new NumericValue(2.0, DOUBLE), EQ, false, "1 == 2.0"),
        Arguments.of(new StringValue("foo"), new NumericValue(2, INT), EQ, false, "\"foo\" == 2"),
        Arguments.of(new NumericValue(1, INT), new StringValue("foo"), EQ, false, "1 == \"foo\""),
        Arguments.of(ValueExpression.NULL, new NumericValue(2, INT), EQ, false, "null == 2"),
        Arguments.of(
            new NumericValue(Double.NaN, DOUBLE),
            new NumericValue(Double.NaN, DOUBLE),
            EQ,
            false,
            "NaN == NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            EQ,
            true,
            "BigDecimal(2) == BigDecimal(2)"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            new NumericValue(BigDecimal.valueOf(1), OBJECT),
            EQ,
            false,
            "BigDecimal(2) == BigDecimal(1)"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            new NumericValue(2, INT),
            EQ,
            true,
            "BigDecimal(2) == 2"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            new NumericValue(2L, LONG),
            EQ,
            true,
            "BigDecimal(2) == 2L"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2.5), OBJECT),
            new NumericValue(2.5, DOUBLE),
            EQ,
            true,
            "BigDecimal(2.5) == 2.5"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            new NumericValue(3, INT),
            EQ,
            false,
            "BigDecimal(2) == 3"),
        Arguments.of(
            new NumericValue(2, INT),
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            EQ,
            true,
            "2 == BigDecimal(2)"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1, INT), GT, false, "1 > 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(2, INT), GT, false, "1 > 2"),
        Arguments.of(
            new NumericValue(1.0, DOUBLE), new NumericValue(1.1, DOUBLE), GT, false, "1.0 > 1.1"),
        Arguments.of(new NumericValue(2, INT), new NumericValue(1, INT), GT, true, "2 > 1"),
        Arguments.of(
            new NumericValue(1.1, DOUBLE), new NumericValue(1.0, DOUBLE), GT, true, "1.1 > 1.0"),
        Arguments.of(new NumericValue(1.1, DOUBLE), new NumericValue(1, INT), GT, true, "1.1 > 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(0.9, DOUBLE), GT, true, "1 > 0.9"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2, INT), GT, false, "null > 2"),
        Arguments.of(new NumericValue(2, INT), ValueExpression.NULL, GT, false, "2 > null"),
        Arguments.of(
            new NumericValue(Double.NaN, DOUBLE),
            new NumericValue(Double.NaN, DOUBLE),
            GT,
            false,
            "NaN > NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            new NumericValue(BigDecimal.valueOf(1), OBJECT),
            GT,
            true,
            "2 > 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(2, INT), GE, false, "1 >= 2"),
        Arguments.of(
            new NumericValue(1.0, DOUBLE), new NumericValue(1.1, DOUBLE), GE, false, "1.0 >= 1.1"),
        Arguments.of(new NumericValue(2, INT), new NumericValue(1, INT), GE, true, "2 >= 1"),
        Arguments.of(
            new NumericValue(1.1, DOUBLE), new NumericValue(1.0, DOUBLE), GE, true, "1.1 >= 1.0"),
        Arguments.of(new NumericValue(1.1, DOUBLE), new NumericValue(1, INT), GE, true, "1.1 >= 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(0.9, DOUBLE), GE, true, "1 >= 0.9"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2, INT), GE, false, "null >= 2"),
        Arguments.of(
            new NumericValue(Double.NaN, DOUBLE),
            new NumericValue(Double.NaN, DOUBLE),
            GE,
            false,
            "NaN >= NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            new NumericValue(BigDecimal.valueOf(1), OBJECT),
            GE,
            true,
            "2 >= 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1, INT), LT, false, "1 < 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(2, INT), LT, true, "1 < 2"),
        Arguments.of(new NumericValue(2, INT), new NumericValue(1, INT), LT, false, "2 < 1"),
        Arguments.of(
            new NumericValue(1.1, DOUBLE), new NumericValue(1.0, DOUBLE), LT, false, "1.1 < 1.0"),
        Arguments.of(
            new NumericValue(1.0, DOUBLE), new NumericValue(1.1, DOUBLE), LT, true, "1.0 < 1.1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1.1, DOUBLE), LT, true, "1 < 1.1"),
        Arguments.of(new NumericValue(0.9, DOUBLE), new NumericValue(1, INT), LT, true, "0.9 < 1"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2, INT), LT, false, "null < 2"),
        Arguments.of(
            new NumericValue(Double.NaN, DOUBLE),
            new NumericValue(Double.NaN, DOUBLE),
            LT,
            false,
            "NaN < NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(1), OBJECT),
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            LT,
            true,
            "1 < 2"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1, INT), LE, true, "1 <= 1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(2, INT), LE, true, "1 <= 2"),
        Arguments.of(new NumericValue(2, INT), new NumericValue(1, INT), LE, false, "2 <= 1"),
        Arguments.of(
            new NumericValue(1.1, DOUBLE), new NumericValue(1.0, DOUBLE), LE, false, "1.1 <= 1.0"),
        Arguments.of(
            new NumericValue(1.0, DOUBLE), new NumericValue(1.1, DOUBLE), LE, true, "1.0 <= 1.1"),
        Arguments.of(new NumericValue(1, INT), new NumericValue(1.1, DOUBLE), LE, true, "1 <= 1.1"),
        Arguments.of(new NumericValue(0.9, DOUBLE), new NumericValue(1, INT), LE, true, "0.9 <= 1"),
        Arguments.of(ValueExpression.NULL, new NumericValue(2, INT), LE, false, "null <= 2"),
        Arguments.of(
            new NumericValue(Double.NaN, DOUBLE),
            new NumericValue(Double.NaN, DOUBLE),
            LE,
            false,
            "NaN <= NaN"),
        Arguments.of(
            new NumericValue(BigDecimal.valueOf(1), OBJECT),
            new NumericValue(BigDecimal.valueOf(2), OBJECT),
            LE,
            true,
            "1 <= 2"),
        Arguments.of(new StringValue("abc"), new StringValue("abd"), LT, true, "'abc' < 'abd'"),
        Arguments.of(new StringValue("abd"), new StringValue("abc"), LT, false, "'abd' < 'abc'"),
        Arguments.of(new StringValue("abc"), new StringValue("abc"), LT, false, "'abc' < 'abc'"),
        Arguments.of(new StringValue("abd"), new StringValue("abc"), GT, true, "'abd' > 'abc'"),
        Arguments.of(new StringValue("abc"), new StringValue("abd"), GT, false, "'abc' > 'abd'"),
        Arguments.of(new StringValue("abc"), new StringValue("abc"), LE, true, "'abc' <= 'abc'"),
        Arguments.of(new StringValue("abd"), new StringValue("abc"), LE, false, "'abd' <= 'abc'"),
        Arguments.of(new StringValue("abc"), new StringValue("abc"), GE, true, "'abc' >= 'abc'"),
        Arguments.of(new StringValue("abc"), new StringValue("abd"), GE, false, "'abc' >= 'abd'"),
        Arguments.of(ref("strValue"), new StringValue("aa"), GT, true, "strValue > 'aa'"),
        Arguments.of(ref("strValue"), new StringValue("zz"), LT, true, "strValue < 'zz'"),
        Arguments.of(
            new StringValue("foo"),
            new StringValue("java.lang.String"),
            INSTANCEOF,
            true,
            "\"foo\" instanceof \"java.lang.String\""),
        Arguments.of(
            new StringValue("foo"),
            new StringValue("java.lang.Object"),
            INSTANCEOF,
            true,
            "\"foo\" instanceof \"java.lang.Object\""),
        Arguments.of(
            ref("random"),
            new StringValue("java.util.Random"),
            INSTANCEOF,
            true,
            "java.util.Random instanceof \"java.util.Random\""),
        Arguments.of(
            ref("strList"),
            new StringValue("java.util.List"),
            INSTANCEOF,
            true,
            "java.util.ArrayList instanceof \"java.util.List\""),
        Arguments.of(
            ref("strList"),
            new StringValue("java.util.Map"),
            INSTANCEOF,
            false,
            "java.util.ArrayList instanceof \"java.util.Map\""),
        Arguments.of(
            ValueExpression.NULL,
            new StringValue("java.lang.String"),
            INSTANCEOF,
            false,
            "null instanceof \"java.lang.String\""),
        Arguments.of(
            ref("intValue"),
            new StringValue("java.lang.Integer"),
            INSTANCEOF,
            true,
            "1 instanceof \"java.lang.Integer\""),
        Arguments.of(
            ref("doubleValue"),
            new StringValue("java.lang.Double"),
            INSTANCEOF,
            true,
            "1.0 instanceof \"java.lang.Double\""),
        Arguments.of(
            ref("option"),
            new StringValue("READ"),
            EQ,
            true,
            "java.nio.file.StandardOpenOption == \"READ\""),
        Arguments.of(
            ref("option"),
            new StringValue("StandardOpenOption.READ"),
            EQ,
            true,
            "java.nio.file.StandardOpenOption == \"StandardOpenOption.READ\""),
        Arguments.of(
            ref("option"),
            new StringValue("java.nio.file.StandardOpenOption.READ"),
            EQ,
            true,
            "java.nio.file.StandardOpenOption == \"java.nio.file.StandardOpenOption.READ\""),
        Arguments.of(
            ref("option"),
            new StringValue("CREATE"),
            EQ,
            false,
            "java.nio.file.StandardOpenOption == \"READ\""),
        Arguments.of(
            new StringValue("READ"),
            ref("option"),
            EQ,
            true,
            "\"READ\" == java.nio.file.StandardOpenOption"),
        // Negative swap: String literal on left, enum ref on right — exercises the SWAP
        // path with a non-matching enum value
        Arguments.of(
            new StringValue("CREATE"),
            ref("option"),
            EQ,
            false,
            "\"CREATE\" == java.nio.file.StandardOpenOption"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("invalidComparisonTypesExprs")
  public void invalidComparisonTypes(
      BooleanExpression expr, String dslExpression, String expectedMsg)
      throws IOException, URISyntaxException {
    doCondition06Failure(expr, dslExpression, expectedMsg);
  }

  private static Stream<Arguments> invalidComparisonTypesExprs() {
    return Stream.of(
        Arguments.of(
            eq(ref("intValue"), value("foo")),
            "intValue == 'foo'",
            "Unsupported equals comparison: long <=> java.lang.String"),
        Arguments.of(
            ge(ref("intValue"), value("foo")),
            "intValue >= 'foo'",
            "Unsupported comparison: long <=> java.lang.String"),
        Arguments.of(
            instanceOf(ref("intValue"), value(5)),
            "intValue instanceof 5",
            "Invalid arguments for instanceof operator"),
        Arguments.of(
            eq(ref("doesNotExist"), value(0)),
            "doesNotExist == 0",
            "Cannot find symbol: doesNotExist"),
        Arguments.of(
            eq(value(true), value(1)),
            "true == 1",
            "Unsupported equals comparison: boolean <=> long"),
        Arguments.of(
            eq(ref("strValue"), value(true)),
            "strValue == true",
            "Unsupported equals comparison: java.lang.String <=> boolean"),
        Arguments.of(
            ge(value(true), value(false)),
            "true >= false",
            "Unsupported comparison: boolean <=> boolean"),
        Arguments.of(
            gt(ref("strValue"), value(5)),
            "strValue > 5",
            "Unsupported comparison: java.lang.String <=> long"));
  }

  // Numeric type matrix: covers primitive-typed refs from CapturedSnapshot25 (int/long/float/
  // double/boolean/byte/short/char) against literals. NumericValue literals widen Byte/Short/
  // Integer → Long and Float → Double, and the visitor widens INT-typed refs → LONG and
  // FLOAT-typed refs → DOUBLE before comparison. BYTE/SHORT/CHAR refs are NOT widened and
  // are not in isNumeric, so they error out at instrumentation time. CHAR is also not in
  // isIntCompatible — so even same-type CHAR == CHAR fails.
  @ParameterizedTest(name = "[{index}] {3}")
  @MethodSource("numericTypeMatrixPositiveExprs")
  public void numericTypeMatrixPositive(
      String methodName, String methodArg, BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition25(methodName, methodArg, expr, dslExpression);
  }

  private static Stream<Arguments> numericTypeMatrixPositiveExprs() {
    // Only probes methods that return a size-1 value (int/byte/short/char/boolean).
    // Probing long/float/double-returning methods hits an unrelated SingleCapturedContext-
    // Instrumenter bug (uses DUP rather than DUP2 to duplicate the return value).
    return Stream.of(
        // INT ref widens to LONG; literal 42 is LONG → LONG-LONG works
        Arguments.of("intFunction", "int", eq(ref("arg"), value(42)), "arg == 42"),
        // Heterogeneous: INT ref (→ LONG) vs DOUBLE literal — addHeterogeneousComparison
        Arguments.of("intFunction", "int", lt(ref("arg"), value(42.5)), "arg < 42.5"),
        // Heterogeneous: INT ref (→ LONG) vs DOUBLE field — same path, ref on right
        Arguments.of("intFunction", "int", gt(ref("arg"), ref("doubleField")), "arg > doubleField"),
        // BOOLEAN ref vs BOOLEAN literal — IF_ICMPEQ path (boolean is isIntCompatible)
        Arguments.of("booleanFunction", "boolean", eq(ref("arg"), value(true)), "arg == true"),
        // BYTE ref vs BYTE ref — same-type IF_ICMPEQ works (arg=0x42, byteField=0 at EXIT)
        Arguments.of(
            "byteFunction", "byte", not(eq(ref("arg"), ref("byteField"))), "arg != byteField"),
        // SHORT ref vs SHORT ref — same-type works
        Arguments.of(
            "shortFunction", "short", not(eq(ref("arg"), ref("shortField"))), "arg != shortField"));
  }

  @ParameterizedTest(name = "[{index}] {3}")
  @MethodSource("numericTypeMatrixFailureExprs")
  public void numericTypeMatrixFailures(
      String methodName,
      String methodArg,
      BooleanExpression expr,
      String dslExpression,
      String expectedMsg)
      throws IOException, URISyntaxException {
    doCondition25Failure(methodName, methodArg, expr, dslExpression, expectedMsg);
  }

  private static Stream<Arguments> numericTypeMatrixFailureExprs() {
    return Stream.of(
        // BYTE ref vs LONG literal — BYTE not widened, not in isNumeric
        Arguments.of(
            "byteFunction",
            "byte",
            eq(ref("arg"), value(0x42)),
            "arg == 66",
            "Unsupported equals comparison: byte <=> long"),
        // SHORT ref vs LONG literal
        Arguments.of(
            "shortFunction",
            "short",
            eq(ref("arg"), value(1001)),
            "arg == 1001",
            "Unsupported equals comparison: short <=> long"),
        // CHAR ref vs LONG literal — CHAR not handled at all
        Arguments.of(
            "charFunction",
            "char",
            eq(ref("arg"), value(97)),
            "arg == 97",
            "Unsupported equals comparison: char <=> long"),
        // CHAR ref vs CHAR ref — fails even for same type (CHAR not in isIntCompatible/isNumeric)
        Arguments.of(
            "charFunction",
            "char",
            eq(ref("arg"), ref("charField")),
            "arg == charField",
            "Unsupported equals comparison: char <=> char"),
        // BOOLEAN ref vs LONG literal under inequality
        Arguments.of(
            "booleanFunction",
            "boolean",
            gt(ref("arg"), value(0)),
            "arg > 0",
            "Unsupported comparison: boolean <=> long"),
        // BOOLEAN ref vs BOOLEAN literal under inequality — also unsupported
        Arguments.of(
            "booleanFunction",
            "boolean",
            gt(ref("arg"), value(true)),
            "arg > true",
            "Unsupported comparison: boolean <=> boolean"));
  }

  @Test
  public void durationAtEntryInvalid() throws IOException, URISyntaxException {
    doCondition06Failure(
        ge(ref("@duration"), value(0L)),
        "@duration >= 0",
        "@duration not available (not at exit)",
        MethodLocation.ENTRY);
  }

  @Test
  public void unsupportedSyntheticSymbol() throws IOException, URISyntaxException {
    doCondition06Failure(eq(ref("@foo"), nullValue()), "@foo == null", "Unsupported symbol: @foo");
  }

  // Operations expecting a String / collection / array applied to a primitive scalar source
  // must fail at instrumentation time rather than emit bytecode that the JVM verifier rejects.
  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("invalidScalarOperationExprs")
  public void invalidScalarOperation(
      BooleanExpression expr, String dslExpression, String expectedMsg)
      throws IOException, URISyntaxException {
    doCondition06Failure(expr, dslExpression, expectedMsg);
  }

  private static Stream<Arguments> invalidScalarOperationExprs() {
    return Stream.of(
        Arguments.of(
            eq(len(ref("intValue")), value(0)),
            "len(intValue) == 0",
            "Unsupported type for len function: int"),
        Arguments.of(
            eq(index(ref("intValue"), value(0)), value(0)),
            "intValue[0] == 0",
            "Unsupported target type for index: int"),
        Arguments.of(
            isEmpty(ref("intValue")),
            "isEmpty(intValue)",
            "Unsupported type for isEmpty function: int"),
        Arguments.of(
            contains(ref("intValue"), value(1)),
            "contains(intValue, 1)",
            "Unsupported type for contains function: int"),
        Arguments.of(
            eq(subString(ref("intValue"), 0, 1), value("x")),
            "substring(intValue, 0, 1) == 'x'",
            "Unsupported type for substring function: int"),
        Arguments.of(
            startsWith(ref("intValue"), new StringValue("x")),
            "startsWith(intValue, 'x')",
            "Unsupported type for startsWith function: int"),
        Arguments.of(
            endsWith(ref("intValue"), new StringValue("x")),
            "endsWith(intValue, 'x')",
            "Unsupported type for endsWith function: int"),
        Arguments.of(
            matches(ref("intValue"), new StringValue("x")),
            "matches(intValue, 'x')",
            "Unsupported type for matches function: int"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("invalidSyntheticExprs")
  public void invalidSynthetic(BooleanExpression expr, String dslExpression, String expectedMsg)
      throws IOException, URISyntaxException {
    doCondition06FailureOnVoidMethod(expr, dslExpression, expectedMsg, MethodLocation.EXIT);
  }

  private static Stream<Arguments> invalidSyntheticExprs() {
    return Stream.of(
        // iteratorOutsideCollectionInvalid
        Arguments.of(
            eq(ref(ITERATOR_REF), value(0)),
            "@it == 0",
            "@it not available if not used with collection functions (filter, any or all)"),
        // returnOnVoidMethodInvalid
        Arguments.of(
            eq(ref("@return"), value(0)), "@return == 0", "@return not available (void?)"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("booleanExprs")
  public void booleanLiteral(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> booleanExprs() {
    return Stream.of(
        Arguments.of(TRUE, "true"),
        Arguments.of(not(BooleanExpression.FALSE), "not(false)"),
        Arguments.of(not(not(TRUE)), "not(not(true))"),
        Arguments.of(not(not(not(BooleanExpression.FALSE))), "not(not(not(false)))"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("isEmptyExprs")
  public void isEmptyOperation(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> isEmptyExprs() {
    return Stream.of(
        Arguments.of(not(isEmpty(ref("strList"))), "!isEmpty(strList)"),
        Arguments.of(not(isEmpty(ref("strArray"))), "!isEmpty(strArray)"),
        Arguments.of(not(isEmpty(ref("strMap"))), "!isEmpty(strMap)"),
        Arguments.of(not(isEmpty(ref("strSet"))), "!isEmpty(strSet)"),
        Arguments.of(not(isEmpty(ref("longArray"))), "!isEmpty(longArray)"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("isDefinedExprs")
  public void isDefinedOperation(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition08(expr, dslExpression);
  }

  private static Stream<Arguments> isDefinedExprs() {
    return Stream.of(
        Arguments.of(
            not(isDefined(getMember(getMember(ref("nullTyped"), "fld"), "fld"))),
            "!isDefined(nullTyped.fld.fld)"),
        Arguments.of(isDefined(ref("@duration")), "isDefined(@duration)"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("lenExprs")
  public void lenExpressions(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> lenExprs() {
    return Stream.of(
        Arguments.of(eq(len(ref("strValue")), value(4)), "len(strValue) == 4"),
        Arguments.of(eq(len(ref("strList")), value(3)), "len(strList) == 3"),
        Arguments.of(eq(len(ref("strArray")), value(2)), "len(strArray) == 2"),
        Arguments.of(eq(len(ref("strMap")), value(1)), "len(strMap) == 1"),
        Arguments.of(eq(len(ref("strSet")), value(2)), "len(strSet) == 2"),
        Arguments.of(eq(len(ref("longArray")), value(10)), "len(longArray) == 10"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("indexExprs")
  public void indexArrayOperation(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> indexExprs() {
    return Stream.of(
        Arguments.of(eq(index(ref("strArray"), value(1)), value("bar")), "strArray[1] == 'bar'"),
        Arguments.of(eq(index(ref("longArray"), value(7)), value(7)), "longArray[7] == 7"),
        Arguments.of(
            eq(index(ref("longArray"), len(ref("strList"))), value(3)),
            "longArray[len(strList)] == 3"),
        Arguments.of(
            eq(index(ref("longArray"), len(index(ref("strList"), value(0)))), value(3)),
            "longArray[len(strList[0])] == 3"),
        Arguments.of(eq(index(ref("strList"), value(1)), value("bar")), "strList[1] == 'bar'"),
        Arguments.of(eq(index(ref("strList"), value(1)), value("bar")), "strList[1] == 'bar'"),
        Arguments.of(
            eq(index(ref("strMap"), value("foo")), value("bar")), "strMap['foo'] == 'bar'"),
        Arguments.of(
            eq(index(ref("strMap"), index(ref("strList"), value(0))), value("bar")),
            "strMap[strList[0]] == 'bar'"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("stringPredicateExprs")
  public void stringPredicateOperation(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> stringPredicateExprs() {
    return Stream.of(
        Arguments.of(
            matches(ref("strValue"), new StringValue("^do[en]+$")),
            "matches(strValue, '^do[en]+$')"),
        Arguments.of(
            matches(ref("objectStrValue"), new StringValue("^foob[ar]+$")),
            "matches(objectStrValue, '^foob[ar]+$')"),
        Arguments.of(
            startsWith(ref("strValue"), new StringValue("don")), "startsWith(strValue, 'don')"),
        Arguments.of(
            startsWith(ref("objectStrValue"), new StringValue("foob")),
            "startsWith(strValue, 'foob')"),
        Arguments.of(
            endsWith(ref("strValue"), new StringValue("one")), "startsWith(strValue, 'one')"),
        Arguments.of(
            endsWith(ref("objectStrValue"), new StringValue("bar")),
            "startsWith(strValue, 'bar')"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("containsExprs")
  public void containsOperation(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> containsExprs() {
    return Stream.of(
        Arguments.of(contains(ref("strValue"), new StringValue("on")), "contains(strValue, 'on')"),
        Arguments.of(contains(ref("strList"), new StringValue("bar")), "contains(strList, 'bar')"),
        Arguments.of(
            contains(ref("longArray"), new NumericValue(9L, LONG)), "contains(longArray, 9)"),
        Arguments.of(
            contains(ref("strArray"), new StringValue("bar")), "contains(strArray, 'bar')"),
        Arguments.of(contains(ref("strSet"), new StringValue("bar")), "contains(strSet, 'bar')"),
        Arguments.of(contains(ref("strMap"), new StringValue("foo")), "contains(strMap, 'foo')"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("substringExprs")
  public void substringOperation(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> substringExprs() {
    return Stream.of(
        Arguments.of(
            eq(subString(ref("strValue"), 1, 3), value("on")), "substring(strValue, 1, 3) == 'on'"),
        Arguments.of(
            eq(subString(ref("objectStrValue"), 1, 3), value("oo")),
            "substring(objectStrValue, 1, 3) == 'oo'"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("fieldAccessGetmemberExprs")
  public void fieldAccessGetmember(
      BooleanExpression expr, String dslExpression, boolean expected)
      throws IOException, URISyntaxException {
    doCondition08(expr, dslExpression, expected);
  }

  private static Stream<Arguments> fieldAccessGetmemberExprs() {
    return Stream.of(
        Arguments.of(
            eq(getMember(getMember(getMember(getMember(ref("this"), "typed"), "fld"), "fld"), "msg"),
                value("hello")),
            "this.typed.fld.fld.msg == 'hello'",
            true),
        Arguments.of(
            eq(getMember(getMember(getMember(ref("typed"), "fld"), "fld"), "msg"), value("hello")),
            "typed.fld.fld.msg == 'hello'",
            true),
        Arguments.of(
            eq(getMember(getMember(getMember(ref("typed"), "fld"), "fld"), "value"), value(42)),
            "typed.fld.fld.value == 42",
            true),
        Arguments.of(
            eq(getMember(ref("maybeStr"), "value"), new StringValue("maybe foo")),
            "maybeStr.value == 'maybe foo'",
            true),
        // OptionalInt/OptionalLong/OptionalDouble present values
        Arguments.of(
            eq(getMember(ref("maybeInt"), "value"), value(42)), "maybeInt.value == 42", true),
        Arguments.of(
            eq(getMember(ref("maybeLong"), "value"), value(1001L)),
            "maybeLong.value == 1001",
            true),
        Arguments.of(
            eq(getMember(ref("maybeDouble"), "value"), value(3.14)),
            "maybeDouble.value == 3.14",
            true),
        // empty Optionals: orElse returns default
        // Optional.empty().value → null; both null-checks evaluate false
        Arguments.of(
            and(
                not(eq(getMember(ref("maybeEmptyStr"), "value"), nullValue())),
                eq(getMember(ref("maybeEmptyStr"), "value"), new StringValue("anything"))),
            "maybeEmptyStr.value != null && maybeEmptyStr.value == 'anything'",
            false),
        Arguments.of(
            eq(getMember(ref("maybeEmptyInt"), "value"), value(0)),
            "maybeEmptyInt.value == 0",
            true),
        Arguments.of(
            eq(getMember(ref("maybeEmptyLong"), "value"), value(0L)),
            "maybeEmptyLong.value == 0",
            true),
        Arguments.of(
            eq(getMember(ref("maybeEmptyDouble"), "value"), value(0.0)),
            "maybeEmptyDouble.value == 0.0",
            true));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("fieldAccessGetmemberFailedExprs")
  public void fieldAccessGetmemberFailed(
      BooleanExpression expr, String dslExpression, String expectedEvalErrorMsg)
      throws IOException, URISyntaxException {
    doCondition08Failure(expr, dslExpression, expectedEvalErrorMsg);
  }

  private static Stream<Arguments> fieldAccessGetmemberFailedExprs() {
    return Stream.of(
      Arguments.of(not(eq(getMember(ref("this"), "unknownField"), nullValue())), "this.unknownField != null", "Field not found: unknownField")
    );
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("fieldAccessGetmemberEvalErrorExprs")
  public void fieldAccessGetmemberEvalError(
      BooleanExpression expr, String dslExpression, String expectedEvalErrorMsg)
      throws IOException, URISyntaxException {
    doCondition08(expr, dslExpression, false, expectedEvalErrorMsg);
  }

  private static Stream<Arguments> fieldAccessGetmemberEvalErrorExprs() {
    return Stream.of(
        Arguments.of(not(eq(getMember(ref("typed"), "unknownField"), nullValue())),
            "typed.unknownField != null", "java.lang.RuntimeException: java.lang.NoSuchFieldException: unknownField")
        );
  }


  @Test
  public void fieldAccessGetmemberException() throws IOException, URISyntaxException {
    doCondition05(
        not(eq(getMember(ref("@exception"), "detailMessage"), nullValue())),
        "@exception.detailMessage != null");
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("fieldAccessStaticExprs")
  public void fieldAccessStatic(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> fieldAccessStaticExprs() {
    return Stream.of(
        Arguments.of(eq(ref("STR_CONSTANT"), value("strConst")), "STR_CONSTANT == 'strConst'"),
        Arguments.of(eq(ref("INT_CONSTANT"), value(1001)), "INT_CONSTANT == 1001"),
        Arguments.of(eq(ref("STATIC_STR"), value("strStatic")), "STATIC_STR == 'strStatic'"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("fieldAccessInheritedExprs")
  public void fieldAccessInherited(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06Inherited(expr, dslExpression);
  }

  private static Stream<Arguments> fieldAccessInheritedExprs() {
    return Stream.of(
        Arguments.of(ge(ref("doubleValue"), value(3.0D)), "doubleValue >= 3"),
        Arguments.of(eq(ref("intValue"), value(48)), "intValue == 48"),
        Arguments.of(ge(getMember(ref("this"), "doubleValue"), value(3.0D)), "this.doubleValue >= 3"),
        Arguments.of(eq(getMember(ref("this"), "intValue"), value(48)), "this.intValue == 48")
    );
  }

  // Two exit-only synthetics combined in a single condition. Both load from the condition
  // method's parameter slots: @duration from timestampVarIndex, @return from returnVarIndex.
  @Test
  public void syntheticDurationAndReturnCombined() throws IOException, URISyntaxException {
    doCondition06(
        and(ge(ref("@duration"), value(0L)), eq(ref("@return"), value(42))),
        "@duration >= 0 && @return == 42");
  }

  @Test
  public void syntheticDuration() throws IOException, URISyntaxException {
    doCondition06(ge(ref("@duration"), value(0L)), "@duration >= 0");
  }

  @Test
  public void syntheticReturn() throws IOException, URISyntaxException {
    doCondition06(eq(ref("@return"), value(42)), "@return == 42");
  }

  // When the probed method returns a String, @return resolves to STRING_TYPE and can be used
  // as the target of String operations — equality, len, startsWith/endsWith.
  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("returnStringExprs")
  public void returnStringOperations(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06ReturnString(expr, dslExpression);
  }

  private static Stream<Arguments> returnStringExprs() {
    // CapturedSnapshot06.returnString() returns "foo" (length 3).
    return Stream.of(
        Arguments.of(eq(ref("@return"), value("foo")), "@return == 'foo'"),
        Arguments.of(eq(len(ref("@return")), value(3)), "len(@return) == 3"),
        Arguments.of(
            startsWith(ref("@return"), new StringValue("fo")), "startsWith(@return, 'fo')"),
        Arguments.of(endsWith(ref("@return"), new StringValue("oo")), "endsWith(@return, 'oo')"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("entryExprs")
  public void conditionAtEntry(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06AtEntry(expr, dslExpression);
  }

  private static Stream<Arguments> entryExprs() {
    // CapturedSnapshot06.f() mutates intValue (24 -> 48) and strValue ("foobar" -> "done")
    // so these conditions only hold when evaluated at ENTRY, not EXIT.
    return Stream.of(
        Arguments.of(eq(ref("intValue"), value(24)), "intValue == 24"),
        Arguments.of(eq(ref("strValue"), value("foobar")), "strValue == 'foobar'"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("exceptionExprs")
  public void syntheticException(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition05(expr, dslExpression);
  }

  private static Stream<Arguments> exceptionExprs() {
    return Stream.of(
        Arguments.of(isDefined(ref("@exception")), "isDefined(@exception)"),
        Arguments.of(
            and(isDefined(ref("@exception")), not(eq(ref("arg"), DSL.nullValue()))),
            "isDefined(@exception) or arg != null"),
        // TODO special case need to be separate and will capture at exit, not at catch uncaught
        // Arguments.of(not(isDefined(ref("@exception"))), "not(isDefined(@exception)"),
        Arguments.of(
            and(
                instanceOf(ref("@exception"), value("CapturedSnapshot05$CustomException")),
                eq(getMember(ref("@exception"), "detailMessage"), value("oops")),
                eq(getMember(ref("@exception"), "additionalMsg"), value("I did it again"))),
            "@exception instanceof \"CapturedSnapshot05$CustomException\" and @exception.detailMessage == 'oops' and @exception.additionalMsg == 'I did it again'"),
        Arguments.of(
            eq(
                getMember(
                    index(getMember(ref("@exception"), "stackTrace"), value(0)), "declaringClass"),
                value("CapturedSnapshot05")),
            "@exception.stackTrace[0].declaringClass == 'CapturedSnapshot05'"),
        Arguments.of(
            any(
                getMember(ref("@exception"), "stackTrace"),
                eq(getMember(ref(ITERATOR_REF), "declaringClass"), value("CapturedSnapshot05"))),
            "any(@exception.stackTrace, {@it.declaringClass == 'CapturedSnapshot05'})"),
        Arguments.of(
            all(
                getMember(ref("@exception"), "stackTrace"),
                instanceOf(ref(ITERATOR_REF), value("java.lang.StackTraceElement"))),
            "all(@exception.stackTrace, {@it instanceof 'java.lang.StackTraceElement'})"));
  }

  @Test
  public void evalError() throws IOException, URISyntaxException {
    doCondition08(
        eq(getMember(getMember(ref("nullTyped"), "fld"), "fld"), value("5")),
        "nullTyped.fld.fld == '5'",
        true,
        "java.lang.NullPointerException");
  }

  // The right operand of instanceof is required to be STRING_TYPE at instrumentation time,
  // but a String *ref* satisfies that check while only revealing the bogus class name at
  // runtime via Class.forName.
  @Test
  public void instanceOfWithRuntimeClassNameLookup() throws IOException, URISyntaxException {
    doCondition06EvalError(
        instanceOf(ref("intValue"), ref("strValue")),
        "intValue instanceof strValue",
        "java.lang.IllegalArgumentException: Class not found: done");
  }

  // Missing map keys resolve to null via ConditionHelper.index, so the condition simply
  // evaluates to false rather than producing an evaluation error — distinct from list /
  // array index OOB above.
  @Test
  public void mapMissingKeyEvaluatesFalse() throws IOException, URISyntaxException {
    doCondition06(
        eq(index(ref("strMap"), value("nonexistent")), value("bar")),
        "strMap['nonexistent'] == 'bar'",
        false);
  }

  // Mirror of the existing or(TRUE, X) / and(FALSE, X) short-circuit cases in binaryExprs:
  // here the would-error expression is on the LEFT and the short-circuit value on the
  // RIGHT. Left-to-right evaluation order must propagate the error — the right operand
  // cannot rescue a faulted left operand.
  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("shortCircuitLeftErrorExprs")
  public void shortCircuitLeftErrorOrdering(
      BooleanExpression expr, String dslExpression, String expectedEvalErrorMsg)
      throws IOException, URISyntaxException {
    doCondition08(expr, dslExpression, true, expectedEvalErrorMsg);
  }

  private static Stream<Arguments> shortCircuitLeftErrorExprs() {
    return Stream.of(
        Arguments.of(
            or(eq(getMember(getMember(ref("nullTyped"), "fld"), "fld"), value("5")), TRUE),
            "nullTyped.fld.fld == '5' || true",
            "java.lang.NullPointerException"),
        Arguments.of(
            and(
                eq(getMember(getMember(ref("nullTyped"), "fld"), "fld"), value("5")),
                BooleanExpression.FALSE),
            "nullTyped.fld.fld == '5' && false",
            "java.lang.NullPointerException"));
  }

  // Pin down semantics for empty / null collections, nested collection ops, and indexing
  // a filter result. Empty collections are derived from `filter(X, FALSE)` to avoid
  // touching CapturedSnapshot06's field set (its fields count is asserted elsewhere).
  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("collectionEdgeExprs")
  public void collectionEdgeCases(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> collectionEdgeExprs() {
    return Stream.of(
        // any over empty collection: false → !any holds
        Arguments.of(
            not(
                any(
                    filter(ref("strList"), BooleanExpression.FALSE),
                    eq(ref(ITERATOR_REF), value("foo")))),
            "!any(filter(strList, false), {@it == 'foo'})"),
        // all over empty collection: true (vacuously)
        Arguments.of(
            all(
                filter(ref("strList"), BooleanExpression.FALSE),
                eq(ref(ITERATOR_REF), value("foo"))),
            "all(filter(strList, false), {@it == 'foo'})"),
        // len(filter(empty)) == 0
        Arguments.of(
            eq(
                len(
                    filter(
                        filter(ref("strList"), BooleanExpression.FALSE),
                        eq(ref(ITERATOR_REF), value("foo")))),
                value(0)),
            "len(filter(filter(strList, false), {@it == 'foo'})) == 0"),
        // any over empty primitive array: false
        Arguments.of(
            not(
                any(
                    filter(ref("longArray"), BooleanExpression.FALSE),
                    eq(ref(ITERATOR_REF), value(0L)))),
            "!any(filter(longArray, false), {@it == 0})"),
        // all over empty primitive array: true
        Arguments.of(
            all(
                filter(ref("longArray"), BooleanExpression.FALSE),
                eq(ref(ITERATOR_REF), value(0L))),
            "all(filter(longArray, false), {@it == 0})"),
        // nested filter inside any: longArray={0..9}, filter > 5 → {6,7,8,9}, any < 8 → true
        Arguments.of(
            any(
                filter(ref("longArray"), gt(ref(ITERATOR_REF), value(5L))),
                lt(ref(ITERATOR_REF), value(8L))),
            "any(filter(longArray, {@it > 5}), {@it < 8})"),
        // filter result indexed: strList at EXIT = ["foo","bar","done"], filter == "bar" → ["bar"],
        // [0] == "bar"
        Arguments.of(
            eq(
                index(filter(ref("strList"), eq(ref(ITERATOR_REF), value("bar"))), value(0)),
                value("bar")),
            "filter(strList, {@it == 'bar'})[0] == 'bar'"));
  }

  // any/all on a null collection: NPE at iteration time → eval error.
  @ParameterizedTest
  @MethodSource("nullCollectionExprs")
  public void nullCollectionEvalError(
      BooleanExpression expr, String dslExpression, String expectedEvalErrorMsg)
      throws IOException, URISyntaxException {
    doCondition06EvalError(expr, dslExpression, expectedEvalErrorMsg);
  }

  private static Stream<Arguments> nullCollectionExprs() {
    return Stream.of(
        Arguments.of(
            any(nullValue(), eq(ref(ITERATOR_REF), value("foo"))),
            "any(null, {@it == 'foo'})",
            "java.lang.NullPointerException: Cannot invoke \"java.util.Collection.iterator()\" because \"collection\" is null"),
        Arguments.of(
            all(nullValue(), eq(ref(ITERATOR_REF), value("foo"))),
            "all(null, {@it == 'foo'})",
            "java.lang.NullPointerException: Cannot invoke \"java.util.Collection.iterator()\" because \"collection\" is null"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("filterExprs")
  public void filterExpressions(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> filterExprs() {
    return Stream.of(
        Arguments.of(
            eq(len(filter(ref("longArray"), lt(ref(ITERATOR_REF), value(2)))), value(2)),
            "len(filter(longArray, {@it < 2})) == 2"),
        Arguments.of(
            eq(
                len(
                    filter(
                        ref("longArray"),
                        lt(ref(ValueReferences.ITERATOR_REF), len(ref("strSet"))))),
                value(2)),
            "len(filter(longArray, {@it < len(strSet)})) == 2"),
        Arguments.of(
            eq(len(filter(ref("strArray"), eq(ref(ITERATOR_REF), value("foo")))), value(1)),
            "len(filter(strArray, {@it == 'foo'})) == 2"),
        Arguments.of(
            eq(len(filter(ref("boolArray"), eq(ref(ITERATOR_REF), value(true)))), value(4)),
            "len(filter(boolArray, {@it == true})) == 4"),
        Arguments.of(
            eq(len(filter(ref("strList"), eq(ref(ITERATOR_REF), value("foo")))), value(1)),
            "len(filter(strList, {@it == 'foo'})) == 1"),
        Arguments.of(
            eq(len(filter(ref("strSet"), eq(ref(ITERATOR_REF), value("foo")))), value(1)),
            "len(filter(strSet, {@it == 'foo'})) == 1"),
        Arguments.of(
            eq(
                len(
                    filter(
                        filter(
                            filter(ref("longArray"), gt(ref(ITERATOR_REF), value(-2))),
                            gt(ref(ITERATOR_REF), value(-1))),
                        gt(ref(ITERATOR_REF), value(0)))),
                value(9)),
            "len(filter(filter(filter(longArray, {@it > -2}), {@it > -1}), {@it > 0})) == 9"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("anyExprs")
  public void anyExpressions(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> anyExprs() {
    return Stream.of(
        Arguments.of(
            any(ref("longArray"), eq(ref(ITERATOR_REF), value(2))), "any(longArray, {@it == 2})"),
        Arguments.of(
            any(ref("longArray"), lt(ref(ValueReferences.ITERATOR_REF), len(ref("strSet")))),
            "any(longArray, {@it < len(strSet)})"),
        Arguments.of(
            any(ref("strArray"), eq(ref(ITERATOR_REF), value("foo"))),
            "any(strArray, {@it == 'foo'})"),
        Arguments.of(
            any(ref("boolArray"), eq(ref(ITERATOR_REF), value(true))),
            "any(boolArray, {@it == true})"),
        Arguments.of(
            any(ref("strList"), eq(ref(ITERATOR_REF), value("foo"))),
            "any(strList, {@it == 'foo'})"),
        Arguments.of(
            any(ref("strSet"), eq(ref(ITERATOR_REF), value("foo"))), "any(strSet, {@it == 'foo'})"),
        Arguments.of(
            any(ref("strList"), instanceOf(ref(ITERATOR_REF), value("java.lang.String"))),
            "any(strList, {@it instanceof 'java.lang.String'})"),
        Arguments.of(
            not(any(ref("strList"), instanceOf(ref(ITERATOR_REF), value("java.lang.Integer")))),
            "!any(strList, {@it instanceof 'java.lang.Integer'})"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("allExprs")
  public void allExpressions(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> allExprs() {
    return Stream.of(
        Arguments.of(
            all(ref("longArray"), ge(ref(ITERATOR_REF), value(0))), "all(longArray, {@it >= 0})"),
        Arguments.of(
            any(ref("longArray"), lt(ref(ValueReferences.ITERATOR_REF), len(ref("strSet")))),
            "any(longArray, {@it < len(strSet)})"),
        Arguments.of(
            all(ref("strArray"), eq(len(ref(ITERATOR_REF)), value(3))),
            "all(strArray, {len(@it) == 3})"),
        Arguments.of(
            all(
                ref("boolArray"),
                or(eq(ref(ITERATOR_REF), value(true)), eq(ref(ITERATOR_REF), value(false)))),
            "all(boolArray, {@it == true || @it == false})"),
        Arguments.of(
            all(ref("strList"), ge(len(ref(ITERATOR_REF)), value(2))),
            "all(strList, {len(@it) >= 2})"),
        Arguments.of(
            all(ref("strSet"), eq(len(ref(ITERATOR_REF)), value(3))),
            "all(strSet, {len(@it) == 3})"),
        Arguments.of(
            all(ref("strList"), instanceOf(ref(ITERATOR_REF), value("java.lang.String"))),
            "all(strList, {@it instanceof 'java.lang.String'})"),
        Arguments.of(
            all(ref("strList"), instanceOf(ref(ITERATOR_REF), value("java.lang.CharSequence"))),
            "all(strList, {@it instanceof 'java.lang.CharSequence'})"));
  }

  void doCondition05(BooleanExpression condition, String dslExpression)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot05";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "triggerUncaughtException", "(String)")
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.onClass(testClass).call("main", "triggerUncaughtException").get();
      Assertions.fail("should not reach this code");
    } catch (ReflectException ex) {
      assertEquals("oops", ex.getCause().getCause().getMessage());
    }
    assertEquals(1, listener.snapshots.size());
    assertNotNull(listener.snapshots.get(0).getCaptures().getReturn());
  }

  void doCondition06(BooleanExpression condition, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(condition, dslExpression, true);
  }

  void doCondition06(BooleanExpression condition, String dslExpression, boolean expected)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "f", "()")
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    if (expected) {
      assertEquals(1, listener.snapshots.size());
      assertNotNull(listener.snapshots.get(0).getCaptures().getReturn());
    } else {
      assertEquals(0, listener.snapshots.size());
    }
  }

  void doCondition06EvalError(
      BooleanExpression condition, String dslExpression, String expectedEvalErrorMsg)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "f", "()")
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertEquals(1, listener.snapshots.size());
    assertEquals(
        expectedEvalErrorMsg, listener.snapshots.get(0).getEvaluationErrors().get(0).getMessage());
    assertEquals(dslExpression, listener.snapshots.get(0).getEvaluationErrors().get(0).getExpr());
  }

  void doCondition06ReturnString(BooleanExpression condition, String dslExpression)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "returnString", "()")
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "returnString").get();
    assertEquals(42, result);
    assertEquals(1, listener.snapshots.size());
    assertNotNull(listener.snapshots.get(0).getCaptures().getReturn());
  }

  void doCondition06AtEntry(BooleanExpression condition, String dslExpression)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "f", "()")
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(MethodLocation.ENTRY)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertEquals(1, listener.snapshots.size());
    assertNotNull(listener.snapshots.get(0).getCaptures().getEntry());
  }

  void doCondition06Failure(BooleanExpression condition, String dslExpression, String expectedMsg)
      throws IOException, URISyntaxException {
    doCondition06Failure(condition, dslExpression, expectedMsg, MethodLocation.EXIT);
  }

  void doCondition06Failure(
      BooleanExpression condition,
      String dslExpression,
      String expectedMsg,
      MethodLocation methodLocation)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "f", "()")
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(methodLocation)
            .build();
    installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertEquals(1, instrumentationListener.results.size());
    InstrumentationResult instrumentationResult0 =
        instrumentationListener.results.get(PROBE_ID.getId());
    assertTrue(instrumentationResult0.isError());
    assertEquals(
        expectedMsg,
        instrumentationResult0.getDiagnostics().get(PROBE_ID).get(0).getThrowable().getMessage());
  }

  void doCondition06FailureOnVoidMethod(
      BooleanExpression condition,
      String dslExpression,
      String expectedMsg,
      MethodLocation methodLocation)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "g", "()")
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(methodLocation)
            .build();
    installProbes(logProbe);
    compileAndLoadClass(CLASS_NAME);
    assertEquals(1, instrumentationListener.results.size());
    InstrumentationResult instrumentationResult0 =
        instrumentationListener.results.get(PROBE_ID.getId());
    assertTrue(instrumentationResult0.isError());
    assertEquals(
        expectedMsg,
        instrumentationResult0.getDiagnostics().get(PROBE_ID).get(0).getThrowable().getMessage());
  }

  void doCondition06Inherited(BooleanExpression condition, String dslExpression)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME + "$Inherited", "f", "()")
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "inherited").get();
    assertEquals(42, result);
    assertEquals(1, listener.snapshots.size());
    assertNotNull(listener.snapshots.get(0).getCaptures().getReturn());
  }

  void doCondition08(BooleanExpression condition, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition08(condition, dslExpression, true, null);
  }

  void doCondition08(BooleanExpression condition, String dslExpression, boolean expectedResult)
      throws IOException, URISyntaxException {
    doCondition08(condition, dslExpression, expectedResult, null);
  }

  void doCondition08(
      BooleanExpression condition,
      String dslExpression,
      boolean expectedResult,
      String expectedEvalErrorMsg)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "5").get();
    assertEquals(3, result);
    if (expectedEvalErrorMsg != null) {
      assertEquals(
          expectedEvalErrorMsg,
          listener.snapshots.get(0).getEvaluationErrors().get(0).getMessage());
      assertEquals(dslExpression, listener.snapshots.get(0).getEvaluationErrors().get(0).getExpr());
    } else {
      if (expectedResult) {
        assertEquals(1, listener.snapshots.size());
        List<?> errors = listener.snapshots.get(0).getEvaluationErrors();
        assertTrue(errors == null || errors.isEmpty());
        assertNotNull(listener.snapshots.get(0).getCaptures().getReturn());
      } else {
        assertEquals(0, listener.snapshots.size());
      }
    }
  }

  void doCondition08Failure(BooleanExpression condition, String dslExpression, String expectedMsg)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "5").get();
    assertEquals(3, result);
    assertEquals(1, instrumentationListener.results.size());
    InstrumentationResult instrumentationResult0 =
        instrumentationListener.results.get(PROBE_ID.getId());
    assertTrue(instrumentationResult0.isError());
    assertEquals(
        expectedMsg,
        instrumentationResult0.getDiagnostics().get(PROBE_ID).get(0).getThrowable().getMessage());

  }

  void doCondition25(
      String methodName, String methodArg, BooleanExpression condition, String dslExpression)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot25";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, methodName, null)
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", methodArg).get();
    assertEquals(42, result);
    assertEquals(1, listener.snapshots.size());
    assertNotNull(listener.snapshots.get(0).getCaptures().getReturn());
  }

  void doCondition25Failure(
      String methodName,
      String methodArg,
      BooleanExpression condition,
      String dslExpression,
      String expectedMsg)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot25";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, methodName, null)
            .when(new ProbeCondition(when(condition), dslExpression))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", methodArg).get();
    assertEquals(1, instrumentationListener.results.size());
    InstrumentationResult instrumentationResult0 =
        instrumentationListener.results.get(PROBE_ID.getId());
    assertTrue(instrumentationResult0.isError());
    assertEquals(
        expectedMsg,
        instrumentationResult0.getDiagnostics().get(PROBE_ID).get(0).getThrowable().getMessage());
  }
}
