import datadog.trace.api.DDTraceId
import datadog.trace.instrumentation.jdbc.SQLCommenter
import datadog.trace.test.util.DDSpecification

class SQLCommenterTest extends DDSpecification {

  def "test encode Sql Comment"() {
    setup:
    injectSysConfig("dd.service", "SqlCommenter")
    injectSysConfig("dd.env", "Test")
    injectSysConfig("dd.version", "TestVersion")

    when:
    SQLCommenter.Builder builder = SQLCommenter.newBuilder()
    builder.withDbService("my-service").withInjectionMode(injectionMode).withSqlInput(query)
    if (injectionMode == "full") {
      builder.withTraceId(traceId).withSpanId(spanId).withSamplingPriority(samplingPriority)
    }
    String sqlWithComment = builder.build().getCommentedSQL()
    sqlWithComment == expected

    then:
    sqlWithComment == expected

    where:
    query                                                                                         | injectionMode | traceId                        | spanId      | samplingPriority   | expected
    "SELECT * FROM foo"                                                                           | "full"        | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/ SELECT * FROM foo"
    "SELECT * FROM foo"                                                                           | "full"        | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/ SELECT * FROM foo"
    "SELECT * FROM foo"                                                                           | "full"        | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | null               | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/ SELECT * FROM foo"
    "SELECT * FROM foo"                                                                           | "full"        | null                           | 9876543210L | Integer.valueOf(1) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "SELECT * from FOO -- test query"                                                             | "full"        | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(1) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * from FOO -- test query"
    "SELECT /* customer-comment */ * FROM foo"                                                    | "full"        | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(1) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT /* customer-comment */ * FROM foo"
    "SELECT * FROM foo"                                                                           | "service"     | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "SELECT /* customer-comment */ * FROM foo"                                                    | "service"     | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT /* customer-comment */ * FROM foo"
    "SELECT * from FOO -- test query"                                                             | "service"     | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * from FOO -- test query"
    "SELECT * FROM foo"                                                                           | "disabled"    | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "SELECT * FROM foo"
    "SELECT * FROM foo /* customer-comment */"                                                    | "disabled"    | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "SELECT * FROM foo /* customer-comment */"
    ""                                                                                            | "full"        | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | ""
    "   "                                                                                         | "full"        | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(1) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/    "
    "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"  | "service"     | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*ddjk its a customer */ SELECT * FROM foo"                                                  | "service"     | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ /*ddjk its a customer */ SELECT * FROM foo"
    "/*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * FROM foo" | "service"     | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "/*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * FROM foo"
    "/*customer-comment*/ SELECT * FROM foo"                                                      | "service"     | DDTraceId.from(Long.MAX_VALUE) | 9876543210L | Integer.valueOf(0) | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ /*customer-comment*/ SELECT * FROM foo"
  }
}
