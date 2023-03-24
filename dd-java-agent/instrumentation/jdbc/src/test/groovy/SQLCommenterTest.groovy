import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.instrumentation.jdbc.SQLCommenter

class SQLCommenterTest extends AgentTestRunner {

  def "test encode Sql Comment"() {
    setup:
    injectSysConfig("dd.service", "SqlCommenter")
    injectSysConfig("dd.env", "Test")
    injectSysConfig("dd.version", "TestVersion")

    when:
    String sqlWithComment = ""
    if (injectionMode == "full") {
      sqlWithComment = SQLCommenter.inject(query, "my-service", traceParent, true)
    } else {
      sqlWithComment = SQLCommenter.inject(query, "my-service")
    }

    sqlWithComment == expected

    then:
    sqlWithComment == expected

    where:
    query                                                                                         | injectionMode | traceParent                                               | expected
    "SELECT * FROM foo"                                                                           | "full"        | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/ SELECT * FROM foo"
    "SELECT * from FOO -- test query"                                                             | "full"        | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * from FOO -- test query"
    "SELECT /* customer-comment */ * FROM foo"                                                    | "full"        | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT /* customer-comment */ * FROM foo"
    "SELECT * FROM foo"                                                                           | "service"     | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/ SELECT * FROM foo"
    "SELECT /* customer-comment */ * FROM foo"                                                    | "service"     | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/ SELECT /* customer-comment */ * FROM foo"
    "SELECT * from FOO -- test query"                                                             | "service"     | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/ SELECT * from FOO -- test query"
    ""                                                                                            | "full"        | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | ""
    "   "                                                                                         | "full"        | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/    "
    "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"  | "service"     | null                                                      | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"                     | "service"     | null                                                      | "/*dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"                                | "service"     | null                                                      | "/*ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*ddpv='TestVersion'*/ SELECT * FROM foo"                                                    | "service"     | null                                                      | "/*ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*ddjk its a customer */ SELECT * FROM foo"                                                  | "service"     | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/ /*ddjk its a customer */ SELECT * FROM foo"
    "/*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * FROM foo" | "service"     | null                                                      | "/*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * FROM foo"
    "/*customer-comment*/ SELECT * FROM foo"                                                      | "service"     | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/ /*customer-comment*/ SELECT * FROM foo"
    "/*traceparent"                                                                               | "service"     | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/ /*traceparent"
  }
}
