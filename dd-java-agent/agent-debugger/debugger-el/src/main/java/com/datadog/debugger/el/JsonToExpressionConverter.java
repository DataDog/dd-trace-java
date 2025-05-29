package com.datadog.debugger.el;

import static com.squareup.moshi.JsonReader.Token.BEGIN_ARRAY;
import static com.squareup.moshi.JsonReader.Token.END_ARRAY;
import static com.squareup.moshi.JsonReader.Token.NUMBER;
import static com.squareup.moshi.JsonReader.Token.STRING;

import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.el.expressions.ContainsExpression;
import com.datadog.debugger.el.expressions.StringPredicateExpression;
import com.datadog.debugger.el.expressions.ValueExpression;
import com.datadog.debugger.el.values.StringValue;
import com.squareup.moshi.JsonReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/** Converts json representation to object model */
public class JsonToExpressionConverter {

  private static final Set<String> PREDICATE_FUNCTIONS =
      new HashSet<>(
          Arrays.asList(
              "not",
              "==",
              "eq",
              "!=",
              "neq",
              "ne",
              ">=",
              "ge",
              ">",
              "gt",
              "<=",
              "le",
              "<",
              "lt",
              "or",
              "and",
              "hasAny",
              "any",
              "hasAll",
              "all",
              "isEmpty",
              "startsWith",
              "endsWith",
              "contains",
              "matches",
              "instanceof",
              "isDefined"));

  @FunctionalInterface
  interface BinaryPredicateExpressionFunction<T extends Expression> {
    BooleanExpression apply(T left, T right);
  }

  @FunctionalInterface
  interface CompositePredicateExpressionFunction<T extends Expression> {
    BooleanExpression apply(T... values);
  }

  public static BooleanExpression createPredicate(JsonReader reader) throws IOException {
    reader.beginObject();
    String predicateType = reader.nextName();
    BooleanExpression expr = internalCreatePredicate(reader, predicateType);
    reader.endObject();
    return expr;
  }

  private static BooleanExpression internalCreatePredicate(JsonReader reader, String predicateType)
      throws IOException {
    switch (predicateType) {
      case "not":
        {
          JsonReader.Token token = reader.peek();
          if (token == BEGIN_ARRAY || token == STRING || token == NUMBER) {
            throw new UnsupportedOperationException(
                "Operation 'not' expects a predicate as its argument");
          }
          return DSL.not(createPredicate(reader));
        }
      case "==":
      case "eq":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'eq' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = createBinaryValuePredicate(reader, DSL::eq);
          reader.endArray();
          return expr;
        }
      case "!=":
      case "neq":
      case "ne":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'ne' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = DSL.not(createBinaryValuePredicate(reader, DSL::eq));
          reader.endArray();
          return expr;
        }
      case ">=":
      case "ge":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'ge' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = createBinaryValuePredicate(reader, DSL::ge);
          reader.endArray();
          return expr;
        }
      case ">":
      case "gt":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'gt' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = createBinaryValuePredicate(reader, DSL::gt);
          reader.endArray();
          return expr;
        }
      case "<=":
      case "le":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'le' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = createBinaryValuePredicate(reader, DSL::le);
          reader.endArray();
          return expr;
        }
      case "<":
      case "lt":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'lt' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = createBinaryValuePredicate(reader, DSL::lt);
          reader.endArray();
          return expr;
        }
      case "instanceof":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'instanceof' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = createBinaryValuePredicate(reader, DSL::instanceOf);
          reader.endArray();
          return expr;
        }
      case "or":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'or' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = createCompositeLogicalPredicate(reader, DSL::or);
          reader.endArray();
          return expr;
        }
      case "and":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'and' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = createCompositeLogicalPredicate(reader, DSL::and);
          reader.endArray();
          return expr;
        }
      case "hasAny":
      case "any":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'hasAny' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = createHasAnyPredicate(reader);
          reader.endArray();
          return expr;
        }
      case "hasAll":
      case "all":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'hasAll' expects the arguments to be defined as array");
          }
          reader.beginArray();
          BooleanExpression expr = createHasAllPredicate(reader);
          reader.endArray();
          return expr;
        }
      case "isEmpty":
        {
          JsonReader.Token token = reader.peek();
          if (token == BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'isEmpty' expects exactly one value argument");
          }
          return DSL.isEmpty(asValueExpression(reader));
        }
      case "isDefined":
        {
          JsonReader.Token token = reader.peek();
          if (token == BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'isUndefined' expects exactly one value argument");
          }
          return DSL.isDefined(asValueExpression(reader));
        }
      case "startsWith":
        {
          return createStringPredicateExpression(reader, DSL::startsWith);
        }
      case "endsWith":
        {
          return createStringPredicateExpression(reader, DSL::endsWith);
        }
      case "contains":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'contains' expects the arguments to be defined as array");
          }
          reader.beginArray();
          ContainsExpression expr =
              DSL.contains(asValueExpression(reader), asValueExpression(reader));
          reader.endArray();
          return expr;
        }
      case "matches":
        {
          return createStringPredicateExpression(reader, DSL::matches);
        }
      default:
        throw new UnsupportedOperationException("Unsupported operation '" + predicateType + "'");
    }
  }

  public static BooleanExpression createHasAnyPredicate(JsonReader reader) throws IOException {
    return DSL.any(asValueExpression(reader), createPredicate(reader));
  }

  public static BooleanExpression createHasAllPredicate(JsonReader reader) throws IOException {
    return DSL.all(asValueExpression(reader), createPredicate(reader));
  }

  public static ValueExpression<?> createCollectionFilter(JsonReader reader) throws IOException {
    return DSL.filter(asValueExpression(reader), createPredicate(reader));
  }

  public static BooleanExpression createBinaryValuePredicate(
      JsonReader reader, BinaryPredicateExpressionFunction<ValueExpression<?>> function)
      throws IOException {
    return function.apply(asValueExpression(reader), asValueExpression(reader));
  }

  public static BooleanExpression createBinaryLogicalPredicate(
      JsonReader reader, BinaryPredicateExpressionFunction<BooleanExpression> function)
      throws IOException {
    return function.apply(createPredicate(reader), createPredicate(reader));
  }

  public static BooleanExpression createCompositeLogicalPredicate(
      JsonReader reader, CompositePredicateExpressionFunction<BooleanExpression> function)
      throws IOException {
    List<BooleanExpression> expressions = new ArrayList<>(2);
    while (reader.hasNext() && reader.peek() != END_ARRAY) {
      expressions.add(createPredicate(reader));
    }
    return function.apply(expressions.toArray(new BooleanExpression[0]));
  }

  public static ValueExpression<?> asValueExpression(JsonReader reader) throws IOException {
    JsonReader.Token currentToken = reader.peek();
    switch (currentToken) {
      case NUMBER:
        {
          // Moshi always consider numbers as decimal. need to parse it as string and detect if dot
          // is present
          // or not to determine ints/longs vs doubles
          String numberStrValue = reader.nextString();
          if (numberStrValue.indexOf('.') > 0) {
            return DSL.value(Double.parseDouble(numberStrValue));
          }
          long longValue = Long.parseLong(numberStrValue);
          // checks if the parsed number fits into integer range
          if (longValue <= Integer.MAX_VALUE && longValue >= Integer.MIN_VALUE) {
            return DSL.value((int) longValue);
          }
          return DSL.value(longValue);
        }
      case STRING:
        {
          String textValue = reader.nextString();
          return DSL.value(textValue);
        }
      case BEGIN_OBJECT:
        {
          reader.beginObject();
          try {
            String fieldName = reader.nextName();
            if (PREDICATE_FUNCTIONS.contains(fieldName)) {
              return DSL.bool(internalCreatePredicate(reader, fieldName));
            }
            switch (fieldName) {
              case "ref":
                {
                  JsonReader.Token token = reader.peek();
                  if (token != STRING) {
                    throw new UnsupportedOperationException(
                        "Operation 'ref' expect exactly one textual argument");
                  }
                  return DSL.ref(reader.nextString());
                }
              case "getmember":
                {
                  JsonReader.Token token = reader.peek();
                  if (token == BEGIN_ARRAY) {
                    reader.beginArray();
                    ValueExpression<?> target = asValueExpression(reader);
                    String name = reader.nextString();
                    reader.endArray();
                    return DSL.getMember(target, name);
                  }
                  throw new UnsupportedOperationException(
                      "Operation 'getmember' expects the arguments to be defined as array");
                }
              case "index":
                {
                  JsonReader.Token token = reader.peek();
                  if (token == BEGIN_ARRAY) {
                    reader.beginArray();
                    ValueExpression<?> target = asValueExpression(reader);
                    ValueExpression<?> key = asValueExpression(reader);
                    reader.endArray();
                    return DSL.index(target, key);
                  }
                  throw new UnsupportedOperationException(
                      "Operation 'index' expects the arguments to be defined as array");
                }
              case "filter":
                {
                  JsonReader.Token token = reader.peek();
                  if (token == BEGIN_ARRAY) {
                    reader.beginArray();
                    ValueExpression<?> filter = createCollectionFilter(reader);
                    reader.endArray();
                    return filter;
                  }
                  throw new UnsupportedOperationException(
                      "Operation 'filter' expects the arguments to be defined as array");
                }
              case "len":
              case "count":
                {
                  JsonReader.Token token = reader.peek();
                  if (token == BEGIN_ARRAY || token == NUMBER) {
                    throw new UnsupportedOperationException(
                        "Operation 'len' expect exactly one textual or object argument");
                  }
                  return DSL.len(asValueExpression(reader));
                }
              case "substring":
                {
                  JsonReader.Token token = reader.peek();
                  if (token == BEGIN_ARRAY) {
                    reader.beginArray();
                    ValueExpression<?> target = asValueExpression(reader);
                    int startIndex = reader.nextInt();
                    int endIndex = reader.nextInt();
                    reader.endArray();
                    return DSL.subString(target, startIndex, endIndex);
                  }
                  throw new UnsupportedOperationException(
                      "Operation 'substring' expects the arguments to be defined as array");
                }
              default:
                throw new UnsupportedOperationException("Invalid value definition: " + fieldName);
            }
          } finally {
            reader.endObject();
          }
        }
      case NULL:
        {
          reader.nextNull();
          return DSL.nullValue();
        }
      case BOOLEAN:
        {
          boolean boolValue = reader.nextBoolean();
          return DSL.value(boolValue);
        }
      default:
        throw new UnsupportedOperationException(
            "Invalid value definition, not supported token: " + currentToken);
    }
  }

  private static StringPredicateExpression createStringPredicateExpression(
      JsonReader reader,
      BiFunction<ValueExpression<?>, StringValue, StringPredicateExpression> predicateFunc)
      throws IOException {
    JsonReader.Token token = reader.peek();
    if (token != BEGIN_ARRAY) {
      throw new UnsupportedOperationException(
          "Operation 'startsWith' expects the arguments to be defined as array");
    }
    reader.beginArray();
    ValueExpression<?> source = asValueExpression(reader);
    StringValue str = new StringValue(reader.nextString());
    StringPredicateExpression expr = predicateFunc.apply(source, str);
    reader.endArray();
    return expr;
  }
}
