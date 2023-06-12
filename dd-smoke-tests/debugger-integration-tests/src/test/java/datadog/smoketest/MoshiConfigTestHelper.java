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
import com.datadog.debugger.el.expressions.IsEmptyExpression;
import com.datadog.debugger.el.expressions.IsUndefinedExpression;
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
      comparisonExpression.getOperator().accept(this);
      try {
        jsonWriter.beginArray();
        comparisonExpression.getLeft().accept(this);
        comparisonExpression.getRight().accept(this);
        jsonWriter.endArray();
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
      return null;
    }

    @Override
    public Void visit(EndsWithExpression endsWithExpression) {
      return null;
    }

    @Override
    public Void visit(FilterCollectionExpression filterCollectionExpression) {
      return null;
    }

    @Override
    public Void visit(HasAllExpression hasAllExpression) {
      return null;
    }

    @Override
    public Void visit(HasAnyExpression hasAnyExpression) {
      return null;
    }

    @Override
    public Void visit(IfElseExpression ifElseExpression) {
      return null;
    }

    @Override
    public Void visit(IfExpression ifExpression) {
      return null;
    }

    @Override
    public Void visit(IsEmptyExpression isEmptyExpression) {
      return null;
    }

    @Override
    public Void visit(IsUndefinedExpression isUndefinedExpression) {
      return null;
    }

    @Override
    public Void visit(LenExpression lenExpression) {
      return null;
    }

    @Override
    public Void visit(MatchesExpression matchesExpression) {
      return null;
    }

    @Override
    public Void visit(NotExpression notExpression) {
      return null;
    }

    @Override
    public Void visit(StartsWithExpression startsWithExpression) {
      return null;
    }

    @Override
    public Void visit(SubStringExpression subStringExpression) {
      return null;
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
      return null;
    }

    @Override
    public Void visit(IndexExpression indexExpression) {
      return null;
    }

    @Override
    public Void visit(WhenExpression whenExpression) {
      try {
        jsonWriter.beginObject();
        whenExpression.getExpression().accept(this);
        jsonWriter.endObject();
      } catch (IOException ex) {
        LOGGER.debug("Cannot serialize: ", ex);
      }
      return null;
    }

    @Override
    public Void visit(BooleanExpression booleanExpression) {
      return null;
    }

    @Override
    public Void visit(ObjectValue objectValue) {
      return null;
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
      return null;
    }

    @Override
    public Void visit(BooleanValue booleanValue) {
      return null;
    }

    @Override
    public Void visit(NullValue nullValue) {
      return null;
    }

    @Override
    public Void visit(ListValue listValue) {
      return null;
    }

    @Override
    public Void visit(MapValue mapValue) {
      return null;
    }
  }
}
