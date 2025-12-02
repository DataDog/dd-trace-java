package datadog.trace.instrumentation.spark

import org.apache.spark.SparkConf
import org.apache.spark.sql.catalyst.expressions.ExprId
import org.apache.spark.sql.catalyst.expressions.PrettyAttribute
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression
import org.apache.spark.sql.catalyst.expressions.aggregate.Count
import org.apache.spark.sql.catalyst.expressions.aggregate.Partial$
import org.apache.spark.sql.execution.InputAdapter
import org.apache.spark.sql.types.NullType
import scala.collection.JavaConverters
import spock.lang.Specification

class SparkPlanSerializerTestCases extends Specification {
  def "should not recurse lists more than 4 levels deep"() {
    given:
    def serializer = new SparkPlanSerializerTest()

    def list = JavaConverters.collectionAsScalaIterable(Arrays.asList("hello", "bye"))
    for (int i=0; i < 10; i++) {
      list = JavaConverters.collectionAsScalaIterable(Arrays.asList(list, list))
    }

    when:
    def res = serializer.safeParseObjectToJson(list, 0)

    then:
    assert res instanceof ArrayList
    assert res.toString() == "[[[[], []], [[], []]], [[[], []], [[], []]]]"
  }

  def "should not parse lists more than 50 long"() {
    given:
    def serializer = new SparkPlanSerializerTest()

    def list = new ArrayList<int>()
    for (int i=0; i < 100; i++) {
      list.add(i)
    }
    list = JavaConverters.collectionAsScalaIterable(list)

    when:
    def res = serializer.safeParseObjectToJson(list, 0)

    then:
    assert res instanceof ArrayList
    assert res.size() == 50
    assert res.toString() == "[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49]"
  }

  def "unknown objects should return null"() {
    given:
    def serializer = new SparkPlanSerializerTest()

    when:
    def res = serializer.safeParseObjectToJson(new SparkConf(), 0)

    then:
    assert res == null
  }

  def "QueryPlan inheritors should return null"() {
    given:
    def serializer = new SparkPlanSerializerTest()

    when:
    def res = serializer.safeParseObjectToJson(new InputAdapter(null), 0)

    then:
    assert res == null
  }

  def "inheritors of safe classes should return string"() {
    given:
    def serializer = new SparkPlanSerializerTest()

    when:
    def res = serializer.safeParseObjectToJson(new PrettyAttribute("test", new NullType()), 0)

    then:
    assert res instanceof String
    assert res == "test"
  }

  def "TreeNode inheritors that are not QueryPlans should return simpleString"() {
    given:
    def serializer = new SparkPlanSerializerTest()
    def expression = new AggregateExpression(new Count(null), new Partial$(), false, new ExprId(0, UUID.randomUUID()))

    when:
    def res = serializer.safeParseObjectToJson(expression, 0)

    then:
    assert res instanceof String
    assert res == "partial_count(null)"
  }
}
