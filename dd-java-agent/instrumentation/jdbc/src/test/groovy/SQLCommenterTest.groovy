import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.instrumentation.jdbc.SQLCommenter

class SQLCommenterTest extends AgentTestRunner {

  def "test encode Sql Comment"() {
    setup:
    injectSysConfig("dd.service", "SqlCommenter")
    injectSysConfig("dd.env", "Test")
    injectSysConfig("dd.version", "TestVersion")

    when:
    SQLCommenter commenter = new SQLCommenter(injectionMode, query, "my-service")
    if (injectionMode == "full") {
      commenter.setTraceparent(traceParent)
    }
    commenter.inject()
    String sqlWithComment = commenter.getCommentedSQL()
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
    "SELECT * FROM foo"                                                                           | "disabled"    | null                                                      | "SELECT * FROM foo"
    "SELECT * FROM foo /* customer-comment */"                                                    | "disabled"    | null                                                      | "SELECT * FROM foo /* customer-comment */"
    ""                                                                                            | "full"        | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | ""
    "   "                                                                                         | "full"        | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/    "
    "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"  | "service"     | null                                                      | "/*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*ddjk its a customer */ SELECT * FROM foo"                                                  | "service"     | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/ /*ddjk its a customer */ SELECT * FROM foo"
    "/*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * FROM foo" | "service"     | null                                                      | "/*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * FROM foo"
    "/*customer-comment*/ SELECT * FROM foo"                                                      | "service"     | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/ /*customer-comment*/ SELECT * FROM foo"
  }
}
