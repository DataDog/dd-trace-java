package datadog.smoketest.appsec.springbootjdbcpostgresql;

import java.util.HashMap;
import java.util.Map;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.conditional.XorExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import net.sf.jsqlparser.util.TablesNamesFinder;

public class SqlDataExtractor extends TablesNamesFinder {

  private final Map<String, Map<String, Object>> resultStructure = new HashMap<>();

  public static Map<String, Map<String, Object>> extractData(String sqlStr)
      throws JSQLParserException {
    SqlDataExtractor dataExtractor = new SqlDataExtractor();
    return dataExtractor.extractData(CCJSqlParserUtil.parse(sqlStr));
  }

  public Map<String, Map<String, Object>> extractData(Statement statement) {
    super.init(false);
    statement.accept(this);
    return resultStructure;
  }

  @Override
  public void visit(Update update) {
    Table table = update.getTable();
    String tableName = table.getFullyQualifiedName();

    Map<String, Object> data = resultStructure.computeIfAbsent(tableName, t -> new HashMap<>());
    ValueVisitor valueVisitor = new ValueVisitor();

    for (UpdateSet updateSet : update.getUpdateSets()) {
      int numOfColumns = updateSet.getColumns().size();
      for (int i = 0; i < numOfColumns; i++) {
        Column column = updateSet.getColumn(i);
        String key = column.getFullyQualifiedName();
        Expression expression = updateSet.getValue(i);

        expression.accept(valueVisitor);
        Object value = valueVisitor.getValue();

        data.put(key, value);
        valueVisitor.resetValue();
      }
    }
    super.visit(update);
  }

  @Override
  public void visit(Insert insert) {
    Table table = insert.getTable();
    String tableName = table.getFullyQualifiedName();

    Map<String, Object> data = resultStructure.computeIfAbsent(tableName, t -> new HashMap<>());
    ValueVisitor valueVisitor = new ValueVisitor();

    int numOfColumns = insert.getColumns().size();
    for (int i = 0; i < numOfColumns; i++) {
      String key = insert.getColumns().get(i).getFullyQualifiedName();
      Expression expression = insert.getValues().getExpressions().get(i);

      expression.accept(valueVisitor);
      Object value = valueVisitor.getValue();

      data.put(key, value);
      valueVisitor.resetValue();
    }

    super.visit(insert);
  }

  static class ValueVisitor implements ExpressionVisitor {

    private Object value;

    private void storeValue(Object value) {
      this.value = value;
    }

    public Object getValue() {
      return value;
    }

    public void resetValue() {
      value = null;
    }

    @Override
    public void visit(BitwiseRightShift aThis) {}

    @Override
    public void visit(BitwiseLeftShift aThis) {}

    @Override
    public void visit(NullValue nullValue) {
      storeValue(null);
    }

    @Override
    public void visit(Function function) {}

    @Override
    public void visit(SignedExpression signedExpression) {}

    @Override
    public void visit(JdbcParameter jdbcParameter) {}

    @Override
    public void visit(JdbcNamedParameter jdbcNamedParameter) {}

    @Override
    public void visit(DoubleValue doubleValue) {
      storeValue(doubleValue.getValue());
    }

    @Override
    public void visit(LongValue longValue) {
      storeValue(longValue.getValue());
    }

    @Override
    public void visit(HexValue hexValue) {
      storeValue(hexValue.getValue());
    }

    @Override
    public void visit(DateValue dateValue) {
      storeValue(dateValue.getValue());
    }

    @Override
    public void visit(TimeValue timeValue) {
      storeValue(timeValue.getValue());
    }

    @Override
    public void visit(TimestampValue timestampValue) {
      storeValue(timestampValue.getValue());
    }

    @Override
    public void visit(Parenthesis parenthesis) {
      parenthesis.getExpression().accept(this);
    }

    @Override
    public void visit(StringValue stringValue) {
      storeValue(stringValue.getValue());
    }

    @Override
    public void visit(Addition addition) {}

    @Override
    public void visit(Division division) {}

    @Override
    public void visit(IntegerDivision division) {}

    @Override
    public void visit(Multiplication multiplication) {}

    @Override
    public void visit(Subtraction subtraction) {}

    @Override
    public void visit(AndExpression andExpression) {}

    @Override
    public void visit(OrExpression orExpression) {}

    @Override
    public void visit(XorExpression orExpression) {}

    @Override
    public void visit(Between between) {}

    @Override
    public void visit(OverlapsCondition overlapsCondition) {}

    @Override
    public void visit(EqualsTo equalsTo) {}

    @Override
    public void visit(GreaterThan greaterThan) {}

    @Override
    public void visit(GreaterThanEquals greaterThanEquals) {}

    @Override
    public void visit(InExpression inExpression) {}

    @Override
    public void visit(FullTextSearch fullTextSearch) {}

    @Override
    public void visit(IsNullExpression isNullExpression) {}

    @Override
    public void visit(IsBooleanExpression isBooleanExpression) {}

    @Override
    public void visit(LikeExpression likeExpression) {}

    @Override
    public void visit(MinorThan minorThan) {}

    @Override
    public void visit(MinorThanEquals minorThanEquals) {}

    @Override
    public void visit(NotEqualsTo notEqualsTo) {}

    @Override
    public void visit(ParenthesedSelect selectBody) {}

    @Override
    public void visit(Column tableColumn) {
      storeValue(tableColumn.getFullyQualifiedName());
    }

    @Override
    public void visit(CaseExpression caseExpression) {}

    @Override
    public void visit(WhenClause whenClause) {}

    @Override
    public void visit(ExistsExpression existsExpression) {}

    @Override
    public void visit(MemberOfExpression memberOfExpression) {}

    @Override
    public void visit(AnyComparisonExpression anyComparisonExpression) {}

    @Override
    public void visit(Concat concat) {}

    @Override
    public void visit(Matches matches) {}

    @Override
    public void visit(BitwiseAnd bitwiseAnd) {}

    @Override
    public void visit(BitwiseOr bitwiseOr) {}

    @Override
    public void visit(BitwiseXor bitwiseXor) {}

    @Override
    public void visit(CastExpression cast) {}

    @Override
    public void visit(Modulo modulo) {}

    @Override
    public void visit(AnalyticExpression aexpr) {}

    @Override
    public void visit(ExtractExpression eexpr) {}

    @Override
    public void visit(IntervalExpression iexpr) {}

    @Override
    public void visit(OracleHierarchicalExpression oexpr) {}

    @Override
    public void visit(RegExpMatchOperator rexpr) {}

    @Override
    public void visit(JsonExpression jsonExpr) {}

    @Override
    public void visit(JsonOperator jsonExpr) {}

    @Override
    public void visit(UserVariable var) {}

    @Override
    public void visit(NumericBind bind) {}

    @Override
    public void visit(KeepExpression aexpr) {}

    @Override
    public void visit(MySQLGroupConcat groupConcat) {}

    @Override
    public void visit(ExpressionList<?> expressionList) {}

    @Override
    public void visit(RowConstructor<?> rowConstructor) {}

    @Override
    public void visit(RowGetExpression rowGetExpression) {}

    @Override
    public void visit(OracleHint hint) {}

    @Override
    public void visit(TimeKeyExpression timeKeyExpression) {}

    @Override
    public void visit(DateTimeLiteralExpression literal) {}

    @Override
    public void visit(NotExpression aThis) {}

    @Override
    public void visit(NextValExpression aThis) {}

    @Override
    public void visit(CollateExpression aThis) {}

    @Override
    public void visit(SimilarToExpression aThis) {}

    @Override
    public void visit(ArrayExpression aThis) {}

    @Override
    public void visit(ArrayConstructor aThis) {}

    @Override
    public void visit(VariableAssignment aThis) {}

    @Override
    public void visit(XMLSerializeExpr aThis) {}

    @Override
    public void visit(TimezoneExpression aThis) {}

    @Override
    public void visit(JsonAggregateFunction aThis) {}

    @Override
    public void visit(JsonFunction aThis) {}

    @Override
    public void visit(ConnectByRootOperator aThis) {}

    @Override
    public void visit(OracleNamedFunctionParameter aThis) {}

    @Override
    public void visit(AllColumns allColumns) {}

    @Override
    public void visit(AllTableColumns allTableColumns) {}

    @Override
    public void visit(AllValue allValue) {}

    @Override
    public void visit(IsDistinctExpression isDistinctExpression) {}

    @Override
    public void visit(GeometryDistance geometryDistance) {}

    @Override
    public void visit(Select selectBody) {}

    @Override
    public void visit(TranscodingFunction transcodingFunction) {}

    @Override
    public void visit(TrimFunction trimFunction) {}

    @Override
    public void visit(RangeExpression rangeExpression) {}
  }
}
