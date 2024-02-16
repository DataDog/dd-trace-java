package datadog.smoketest;

import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.expressions.BinaryExpression;
import com.datadog.debugger.el.expressions.BinaryOperator;
import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.el.expressions.ComparisonExpression;
import com.datadog.debugger.el.expressions.ComparisonOperator;
import com.datadog.debugger.el.expressions.ContainsExpression;
import com.datadog.debugger.el.expressions.EndsWithExpression;
import com.datadog.debugger.el.expressions.FilterCollectionExpression;
import com.datadog.debugger.el.expressions.GetMemberExpression;
import com.datadog.debugger.el.expressions.HasAllExpression;
import com.datadog.debugger.el.expressions.HasAnyExpression;
import com.datadog.debugger.el.expressions.IfElseExpression;
import com.datadog.debugger.el.expressions.IfExpression;
import com.datadog.debugger.el.expressions.IndexExpression;
import com.datadog.debugger.el.expressions.IsDefinedExpression;
import com.datadog.debugger.el.expressions.IsEmptyExpression;
import com.datadog.debugger.el.expressions.LenExpression;
import com.datadog.debugger.el.expressions.MatchesExpression;
import com.datadog.debugger.el.expressions.NotExpression;
import com.datadog.debugger.el.expressions.StartsWithExpression;
import com.datadog.debugger.el.expressions.SubStringExpression;
import com.datadog.debugger.el.expressions.ValueRefExpression;
import com.datadog.debugger.el.expressions.WhenExpression;
import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.NullValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.ObjectValue;
import com.datadog.debugger.el.values.StringValue;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoshiConfigTestHelper {
  public static Moshi createMoshiConfig() {
    ProbeCondition.ProbeConditionJsonAdapter probeConditionJsonAdapter =
        new ProbeConditionJsonAdapter();
    return new Moshi.Builder()
        .add(ProbeCondition.class, probeConditionJsonAdapter)
        .add(DebuggerScript.class, probeConditionJsonAdapter)
        .add(ValueScript.class, new ValueScript.ValueScriptAdapter())
        .add(LogProbe.Segment.class, new LogProbe.Segment.SegmentJsonAdapter())
        .add(Where.SourceLine[].class, new Where.SourceLineAdapter())
        .add(ProbeDefinition.Tag[].class, new ProbeDefinition.TagAdapter())
        .build();
  }

  private static class ProbeConditionJsonAdapter extends ProbeCondition.ProbeConditionJsonAdapter {
    @Override
    public void toJson(@NonNull JsonWriter jsonWriter, ProbeCondition value) throws IOException {
      if (value == null) {
        jsonWriter.nullValue();
        return;
      }
      jsonWriter.beginObject();
      jsonWriter.name("dsl");
      jsonWriter.value(value.getDslExpression());
      jsonWriter.name("json");
      (new JsonConditionVisitor(jsonWriter)).visit(value.getWhen());
      jsonWriter.endObject();
    }
  }

  private static class JsonConditionVisitor implements Visitor<Void> {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonConditionVisitor.class);

    private final JsonWriter jsonWriter;

    public JsonConditionVisitor(JsonWriter jsonWriter) {
      this.jsonWriter = jsonWriter;
    }

    @Override
    public Void visit(BinaryExpression binaryExpression) {
      try {
        jsonWriter.beginObject();
        binaryExpression.getOperator().accept(this);
        jsonWriter.beginArray();
        binaryExpression.getLeft().accept(this);
        binaryExpression.getRight().accept(this);
        jsonWriter.endArray();
        jsonWriter.endObject();
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(BinaryOperator operator) {
      try {
        jsonWriter.name(operator.name().toLowerCase());
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(ComparisonExpression comparisonExpression) {
      try {
        jsonWriter.beginObject();
        comparisonExpression.getOperator().accept(this);
        jsonWriter.beginArray();
        comparisonExpression.getLeft().accept(this);
        comparisonExpression.getRight().accept(this);
        jsonWriter.endArray();
        jsonWriter.endObject();
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(ComparisonOperator operator) {
      try {
        jsonWriter.name(operator.name().toLowerCase());
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(ContainsExpression containsExpression) {
      throw new UnsupportedOperationException("contains expression");
    }

    @Override
    public Void visit(EndsWithExpression endsWithExpression) {
      throw new UnsupportedOperationException("endsWith expression");
    }

    @Override
    public Void visit(FilterCollectionExpression filterCollectionExpression) {
      throw new UnsupportedOperationException("filter expression");
    }

    @Override
    public Void visit(HasAllExpression hasAllExpression) {
      throw new UnsupportedOperationException("hasAll expression");
    }

    @Override
    public Void visit(HasAnyExpression hasAnyExpression) {
      throw new UnsupportedOperationException("hasAny expression");
    }

    @Override
    public Void visit(IfElseExpression ifElseExpression) {
      throw new UnsupportedOperationException("ifElse expression");
    }

    @Override
    public Void visit(IfExpression ifExpression) {
      throw new UnsupportedOperationException("if expression");
    }

    @Override
    public Void visit(IsEmptyExpression isEmptyExpression) {
      throw new UnsupportedOperationException("isEmpty expression");
    }

    @Override
    public Void visit(IsDefinedExpression isDefinedExpression) {
      throw new UnsupportedOperationException("isDefined expression");
    }

    @Override
    public Void visit(LenExpression lenExpression) {
      try {
        jsonWriter.beginObject();
        jsonWriter.name("len");
        lenExpression.getSource().accept(this);
        jsonWriter.endObject();
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(MatchesExpression matchesExpression) {
      throw new UnsupportedOperationException("matches expression");
    }

    @Override
    public Void visit(NotExpression notExpression) {
      try {
        jsonWriter.beginObject();
        jsonWriter.name("not");
        notExpression.getPredicate().accept(this);
        jsonWriter.endObject();
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(StartsWithExpression startsWithExpression) {
      throw new UnsupportedOperationException("startsWith expression");
    }

    @Override
    public Void visit(SubStringExpression subStringExpression) {
      throw new UnsupportedOperationException("subString expression");
    }

    @Override
    public Void visit(ValueRefExpression valueRefExpression) {
      try {
        jsonWriter.beginObject();
        jsonWriter.name("ref");
        jsonWriter.value(valueRefExpression.getSymbolName());
        jsonWriter.endObject();
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(GetMemberExpression getMemberExpression) {
      try {
        jsonWriter.beginObject();
        jsonWriter.name("getmember");
        jsonWriter.beginArray();
        getMemberExpression.getTarget().accept(this);
        jsonWriter.value(getMemberExpression.getMemberName());
        jsonWriter.endArray();
        jsonWriter.endObject();
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(IndexExpression indexExpression) {
      try {
        jsonWriter.beginObject();
        jsonWriter.name("index");
        jsonWriter.beginArray();
        indexExpression.getTarget().accept(this);
        indexExpression.getKey().accept(this);
        jsonWriter.endArray();
        jsonWriter.endObject();
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(WhenExpression whenExpression) {
      whenExpression.getExpression().accept(this);
      return null;
    }

    @Override
    public Void visit(BooleanExpression booleanExpression) {
      throw new UnsupportedOperationException("boolean expression");
    }

    @Override
    public Void visit(ObjectValue objectValue) {
      throw new UnsupportedOperationException("objectValue");
    }

    @Override
    public Void visit(StringValue stringValue) {
      try {
        jsonWriter.jsonValue(stringValue.getValue());
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(NumericValue numericValue) {
      try {
        if (numericValue.getValue() instanceof Long) {
          jsonWriter.value(numericValue.getValue().longValue());
        } else if (numericValue.getValue() instanceof Double) {
          jsonWriter.value(numericValue.getValue().doubleValue());
        } else {
          throw new UnsupportedOperationException(
              "numeric value unsupported:" + numericValue.getValue().getClass());
        }
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(BooleanValue booleanValue) {
      throw new UnsupportedOperationException("booleanValue");
    }

    @Override
    public Void visit(NullValue nullValue) {
      try {
        jsonWriter.nullValue();
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(ListValue listValue) {
      throw new UnsupportedOperationException("listValue");
    }

    @Override
    public Void visit(MapValue mapValue) {
      throw new UnsupportedOperationException("mapValue");
    }
  }
}
