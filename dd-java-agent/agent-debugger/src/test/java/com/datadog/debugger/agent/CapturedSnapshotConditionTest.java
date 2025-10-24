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
import static com.datadog.debugger.el.ValueType.DOUBLE;
import static com.datadog.debugger.el.ValueType.FLOAT;
import static com.datadog.debugger.el.ValueType.INT;
import static com.datadog.debugger.el.ValueType.LONG;
import static com.datadog.debugger.el.ValueType.OBJECT;
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
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.util.TestSnapshotListener;
import datadog.trace.bootstrap.debugger.MethodLocation;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.util.stream.Stream;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
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
  public void binaryComparisons(BooleanExpression expr, String dslExpression) throws IOException, URISyntaxException {
    doCondition08(expr, dslExpression);
  }

  public static Stream<Arguments> binaryExprs() {
    return Stream.of(
        Arguments.of(and(and(eq(ref("arg"), value("5")), eq(value(5), value(5))), eq(value(true), value(true))),
          "arg == '5' && 5 == 5 && true == true"),
        Arguments.of(not(eq(ref("arg"), nullValue())), "arg != null"),
        Arguments.of(and(gt(ref("arg"), value("4")), gt(value(5), value(4))), "arg > '4' && 5 > 4"),
        Arguments.of(
            and(
                and(ge(ref("arg"), value("4")), ge(value(5), value(4))),
                and(ge(ref("arg"), value("5")), ge(value(5), value(5)))),
            "arg >= '4' && 5 >= 4 && arg >= '5' && 5 >= 5"),
        Arguments.of(and(lt(ref("arg"), value("6")), lt(value(4), value(5))), "arg < '6' && 4 < 5"),
        Arguments.of(
            and(
                and(le(ref("arg"), value("6")), le(value(4), value(5))),
                and(le(ref("arg"), value("5")), le(value(5), value(5)))),
            "arg <= '4' && 5 <= 4 && arg <= '5' && 5 <= 5"),
        Arguments.of(
            or(eq(ref("arg"), value("4")), eq(ref("arg"), value("5"))), "arg == '4' || arg == '5'")
    );
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
            "\"READ\" == java.nio.file.StandardOpenOption"));
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
            "Unsupported comparison: long <=> java.lang.String"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("booleanExprs")
  public void boolean_literal(BooleanExpression expr, String dslExpression)
      throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> booleanExprs() {
    return Stream.of(
        Arguments.of(BooleanExpression.TRUE, "true"),
        Arguments.of(not(BooleanExpression.FALSE), "not(false)"),
        Arguments.of(not(not(BooleanExpression.TRUE)), "not(not(true))"),
        Arguments.of(not(not(not(BooleanExpression.FALSE))), "not(not(not(false)))"));
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("isEmptyExprs")
  public void isEmpty_operation(BooleanExpression expr, String dslExpression)
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
  public void isDefined_operation(BooleanExpression expr, String dslExpression)
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
  public void index_array_operation(BooleanExpression expr, String dslExpression)
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
  public void string_predicate_operation(BooleanExpression expr, String dslExpression)
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
  public void contains_operation(BooleanExpression expr, String dslExpression)
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
  public void substring_operation(BooleanExpression expr, String dslExpression)
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

  @Test
  public void field_access_getmember() throws IOException, URISyntaxException {
    doCondition08(
        eq(getMember(getMember(getMember(ref("typed"), "fld"), "fld"), "msg"), value("hello")),
        "typed.fld.fld.msg == 'hello'");
  }

  @Test
  public void field_access_getmember_private() throws IOException, URISyntaxException {
    doCondition08(
        eq(getMember(getMember(getMember(ref("typed"), "fld"), "fld"), "value"), value(42)),
        "typed.fld.fld.value == 42");
  }

  @Test
  public void field_access_getmember_exception() throws IOException, URISyntaxException {
    doCondition05(
        not(eq(getMember(ref("@exception"), "detailMessage"), nullValue())),
        "@exception.detailMessage != null");
  }

  @Test
  public void field_access_getmember_optional() throws IOException, URISyntaxException {
    doCondition08(
        eq(getMember(ref("maybeStr"), "value"), new StringValue("maybe foo")),
        "maybeStr.value == 'maybe foo'");
  }

  @Test
  public void field_access_protected_inherited() throws IOException, URISyntaxException {
    doCondition06Inherited(ge(ref("doubleValue"), value(3.0D)), "this.doubleValue >= 3");
  }

  @Test
  public void field_access_private_inherited() throws IOException, URISyntaxException {
    doCondition06Inherited(eq(ref("intValue"), value(48)), "this.intValue == 48");
  }

  @Test
  public void synthetic_duration() throws IOException, URISyntaxException {
    doCondition06(ge(ref("@duration"), value(0L)), "@duration >= 0");
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("exceptionExprs")
  public void synthetic_exception(BooleanExpression expr, String dslExpression)
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
            "@exception.stackTrace[0].declaringClass == 'CapturedSnapshot05'"));
  }

  @Test
  public void eval_error() throws IOException, URISyntaxException {
    doCondition08(
        eq(getMember(getMember(ref("nullTyped"), "fld"), "fld"), value("5")),
        "nullTyped.fld.fld == '5'",
        "java.lang.NullPointerException");
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("filterExprs")
  public void filterExpressions(BooleanExpression expr, String dslExpression) throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> filterExprs() {
    return Stream.of(
        Arguments.of(eq(len(filter(ref("longArray"), lt(ref(ITERATOR_REF), value(2)))), value(2)), "len(filter(longArray, {@it < 2})) == 2"),
        Arguments.of(eq(len(filter(ref("longArray"), lt(ref(ValueReferences.ITERATOR_REF), len(ref("strSet"))))), value(2)), "len(filter(longArray, {@it < len(strSet)})) == 2"),
        Arguments.of(eq(len(filter(ref("strArray"), eq(ref(ITERATOR_REF), value("foo")))), value(1)), "len(filter(strArray, {@it == 'foo'})) == 2"),
        Arguments.of(eq(len(filter(ref("boolArray"), eq(ref(ITERATOR_REF), value(true)))), value(4)), "len(filter(boolArray, {@it == true})) == 4"),
        Arguments.of(eq(len(filter(ref("strList"), eq(ref(ITERATOR_REF), value("foo")))), value(1)), "len(filter(strList, {@it == 'foo'})) == 1"),
        Arguments.of(eq(len(filter(ref("strSet"), eq(ref(ITERATOR_REF), value("foo")))), value(1)), "len(filter(strSet, {@it == 'foo'})) == 1"),
        Arguments.of(
            eq(len(filter(
                filter(
                    filter(
                        ref("longArray"),
                        gt(ref(ITERATOR_REF), value(-2))
                    ),
                    gt(ref(ITERATOR_REF), value(-1))
                ),
                gt(ref(ITERATOR_REF), value(0))
            )), value(9)),
            "len(filter(filter(filter(longArray, {@it > -2}), {@it > -1}), {@it > 0})) == 9"
        )
    );
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("anyExprs")
  public void anyExpressions(BooleanExpression expr, String dslExpression) throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> anyExprs() {
    return Stream.of(
        Arguments.of(any(ref("longArray"), eq(ref(ITERATOR_REF), value(2))),  "any(longArray, {@it == 2})"),
        Arguments.of(any(ref("longArray"), lt(ref(ValueReferences.ITERATOR_REF), len(ref("strSet")))), "any(longArray, {@it < len(strSet)})"),
        Arguments.of(any(ref("strArray"), eq(ref(ITERATOR_REF), value("foo"))), "any(strArray, {@it == 'foo'})"),
        Arguments.of(any(ref("boolArray"), eq(ref(ITERATOR_REF), value(true))), "any(boolArray, {@it == true})"),
        Arguments.of(any(ref("strList"), eq(ref(ITERATOR_REF), value("foo"))), "any(strList, {@it == 'foo'})"),
        Arguments.of(any(ref("strSet"), eq(ref(ITERATOR_REF), value("foo"))), "any(strSet, {@it == 'foo'})")
    );
  }

  @ParameterizedTest(name = "[{index}] {1}")
  @MethodSource("allExprs")
  public void allExpressions(BooleanExpression expr, String dslExpression) throws IOException, URISyntaxException {
    doCondition06(expr, dslExpression);
  }

  private static Stream<Arguments> allExprs() {
    return Stream.of(
        Arguments.of(all(ref("longArray"), ge(ref(ITERATOR_REF), value(0))),  "all(longArray, {@it >= 0})"),
        Arguments.of(any(ref("longArray"), lt(ref(ValueReferences.ITERATOR_REF), len(ref("strSet")))), "any(longArray, {@it < len(strSet)})"),
        Arguments.of(all(ref("strArray"), eq(len(ref(ITERATOR_REF)), value(3))), "all(strArray, {len(@it) == 3})"),
        Arguments.of(all(ref("boolArray"), or(eq(ref(ITERATOR_REF), value(true)), eq(ref(ITERATOR_REF), value(false)))), "all(boolArray, {@it == true || @it == false})"),
        Arguments.of(all(ref("strList"), ge(len(ref(ITERATOR_REF)), value(2))), "all(strList, {len(@it) >= 2})"),
        Arguments.of(all(ref("strSet"), eq(len(ref(ITERATOR_REF)), value(3))), "all(strSet, {len(@it) == 3})")
    );
  }

  // TODO add tests for errors (types)

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

  void doCondition06Failure(BooleanExpression condition, String dslExpression, String expectedMsg)
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
    doCondition08(condition, dslExpression, null);
  }

  void doCondition08(BooleanExpression condition, String dslExpression, String expectedEvalErrorMsg)
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
    assertEquals(1, listener.snapshots.size());
    if (expectedEvalErrorMsg != null) {
      assertEquals(
          expectedEvalErrorMsg,
          listener.snapshots.get(0).getEvaluationErrors().get(0).getMessage());
      assertEquals(dslExpression, listener.snapshots.get(0).getEvaluationErrors().get(0).getExpr());
    } else {
      assertNotNull(listener.snapshots.get(0).getCaptures().getReturn());
    }
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
}
