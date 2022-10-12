package com.datadog.debugger.el;

import static com.squareup.moshi.JsonReader.Token.BEGIN_ARRAY;
import static com.squareup.moshi.JsonReader.Token.END_ARRAY;
import static com.squareup.moshi.JsonReader.Token.NUMBER;
import static com.squareup.moshi.JsonReader.Token.STRING;

import com.datadog.debugger.el.expressions.PredicateExpression;
import com.datadog.debugger.el.expressions.ValueExpression;
import com.squareup.moshi.JsonReader;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Converts json representation to object model */
public class JsonToExpressionConverter {

  @FunctionalInterface
  interface BinaryPredicateExpressionFunction<T extends Expression> {
    PredicateExpression apply(T left, T right);
  }

  @FunctionalInterface
  interface CompositePredicateExpressionFunction<T extends Expression> {
    PredicateExpression apply(T... values);
  }

  public static PredicateExpression createPredicate(JsonReader reader) throws IOException {
    reader.beginObject();
    String predicateType = reader.nextName();
    PredicateExpression expr = null;
    switch (predicateType) {
      case "not":
        {
          JsonReader.Token token = reader.peek();
          if (token == BEGIN_ARRAY || token == STRING || token == NUMBER) {
            throw new UnsupportedOperationException(
                "Operation 'not' expects a predicate as its argument");
          }
          expr = DSL.not(createPredicate(reader));
          break;
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
          expr = createBinaryValuePredicate(reader, DSL::eq);
          reader.endArray();
          break;
        }
      case "!=":
      case "neq":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'neq' expects the arguments to be defined as array");
          }
          reader.beginArray();
          expr = DSL.not(createBinaryValuePredicate(reader, DSL::eq));
          reader.endArray();
          break;
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
          expr = createBinaryValuePredicate(reader, DSL::ge);
          reader.endArray();
          break;
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
          expr = createBinaryValuePredicate(reader, DSL::gt);
          reader.endArray();
          break;
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
          expr = createBinaryValuePredicate(reader, DSL::le);
          reader.endArray();
          break;
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
          expr = createBinaryValuePredicate(reader, DSL::lt);
          reader.endArray();
          break;
        }
      case "or":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'or' expects the arguments to be defined as array");
          }
          reader.beginArray();
          expr = createCompositeLogicalPredicate(reader, DSL::or);
          reader.endArray();
          break;
        }
      case "and":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'and' expects the arguments to be defined as array");
          }
          reader.beginArray();
          expr = createCompositeLogicalPredicate(reader, DSL::and);
          reader.endArray();
          break;
        }
      case "hasAny":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'hasAny' expects the arguments to be defined as array");
          }
          reader.beginArray();
          expr = createHasAnyPredicate(reader);
          reader.endArray();
          break;
        }
      case "hasAll":
        {
          JsonReader.Token token = reader.peek();
          if (token != BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'hasAll' expects the arguments to be defined as array");
          }
          reader.beginArray();
          expr = createHasAllPredicate(reader);
          reader.endArray();
          break;
        }
      case "isEmpty":
        {
          JsonReader.Token token = reader.peek();
          if (token == BEGIN_ARRAY) {
            throw new UnsupportedOperationException(
                "Operation 'isEmpty' expects exactly one value argument");
          }
          expr = DSL.isEmpty(asValueExpression(reader));
          break;
        }
      default:
        throw new UnsupportedOperationException("Unsupported operation '" + predicateType + "'");
    }
    reader.endObject();
    return expr;
  }

  public static PredicateExpression createHasAnyPredicate(JsonReader reader) throws IOException {
    return DSL.any(asValueExpression(reader), createPredicate(reader));
  }

  public static PredicateExpression createHasAllPredicate(JsonReader reader) throws IOException {
    return DSL.all(asValueExpression(reader), createPredicate(reader));
  }

  public static ValueExpression<?> createCollectionFilter(JsonReader reader) throws IOException {
    return DSL.filter(asValueExpression(reader), createPredicate(reader));
  }

  public static PredicateExpression createBinaryValuePredicate(
      JsonReader reader, BinaryPredicateExpressionFunction<ValueExpression<?>> function)
      throws IOException {
    return function.apply(asValueExpression(reader), asValueExpression(reader));
  }

  public static PredicateExpression createBinaryLogicalPredicate(
      JsonReader reader, BinaryPredicateExpressionFunction<PredicateExpression> function)
      throws IOException {
    return function.apply(createPredicate(reader), createPredicate(reader));
  }

  public static PredicateExpression createCompositeLogicalPredicate(
      JsonReader reader, CompositePredicateExpressionFunction<PredicateExpression> function)
      throws IOException {
    List<PredicateExpression> expressions = new ArrayList<>(2);
    while (reader.hasNext() && reader.peek() != END_ARRAY) {
      expressions.add(createPredicate(reader));
    }
    return function.apply(expressions.toArray(new PredicateExpression[0]));
  }

  public static ValueExpression<?> asValueExpression(JsonReader reader) throws IOException {
    ValueExpression<?> value;
    switch (reader.peek()) {
      case NUMBER:
        {
          // handle int/long?
          value = DSL.value(reader.nextDouble());
          break;
        }
      case STRING:
        {
          String textValue = reader.nextString();
          if (ValueReferences.isRefExpression(textValue)) {
            value = DSL.ref(textValue);
          } else {
            value = DSL.value(textValue);
          }
          break;
        }
      case BEGIN_OBJECT:
        {
          reader.beginObject();
          try {
            String fieldName = reader.nextName();
            switch (fieldName) {
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
              default:
                throw new UnsupportedOperationException("Invalid value definition: " + fieldName);
            }
          } finally {
            reader.endObject();
          }
        }
      default:
        throw new UnsupportedOperationException("Invalid value definition: ");
    }
    return value;
  }
}
