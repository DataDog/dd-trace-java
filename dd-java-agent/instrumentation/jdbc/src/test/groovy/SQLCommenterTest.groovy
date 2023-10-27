import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.instrumentation.jdbc.SQLCommenter

class SQLCommenterTest extends AgentTestRunner {

  def "test encode Sql Comment"() {
    setup:
    injectSysConfig("dd.service", ddService)
    injectSysConfig("dd.env", ddEnv)
    injectSysConfig("dd.version", ddVersion)

    when:
    String sqlWithComment = ""
    if (injectTrace) {
      sqlWithComment = SQLCommenter.inject(query, dbService, traceParent, true)
    } else {
      sqlWithComment = SQLCommenter.inject(query, dbService)
    }

    sqlWithComment == expected

    then:
    sqlWithComment == expected

    where:
    query                                                                                         | ddService      | ddEnv  | dbService    | ddVersion     | injectTrace | traceParent                                               | expected
    "SELECT * FROM foo"                                                                           | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | true        | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "{call dogshelterProc(?, ?)}"                                                                 | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | true        | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "{call dogshelterProc(?, ?)} /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo"                                                                           | ""             | "Test" | ""           | "TestVersion" | true        | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo"                                                                           | ""             | "Test" | "my-service" | "TestVersion" | true        | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo"                                                                           | ""             | "Test" | ""           | ""            | true        | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*dde='Test',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo"                                                                           | ""             | ""     | ""           | ""            | true        | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * from FOO -- test query"                                                             | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | true        | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "SELECT * from FOO -- test query /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/"
    "SELECT /* customer-comment */ * FROM foo"                                                    | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | true        | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "SELECT /* customer-comment */ * FROM foo /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/"
    "SELECT * FROM foo"                                                                           | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/"
    "SELECT /* customer-comment */ * FROM foo"                                                    | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "SELECT /* customer-comment */ * FROM foo /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/"
    "SELECT * from FOO -- test query"                                                             | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "SELECT * from FOO -- test query /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/"
    ""                                                                                            | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | true        | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | ""
    "   "                                                                                         | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | true        | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "    /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/"
    "SELECT * FROM foo /*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"  | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "SELECT * FROM foo /*dddbs='my-service',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"
    "SELECT * FROM foo /*dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"                     | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "SELECT * FROM foo /*dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"
    "SELECT * FROM foo /*ddps='SqlCommenter',ddpv='TestVersion'*/"                                | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "SELECT * FROM foo /*ddps='SqlCommenter',ddpv='TestVersion'*/"
    "SELECT * FROM foo /*ddpv='TestVersion'*/"                                                    | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "SELECT * FROM foo /*ddpv='TestVersion'*/"
    "/*ddjk its a customer */ SELECT * FROM foo"                                                  | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "/*ddjk its a customer */ SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/"
    "SELECT * FROM foo /*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/" | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "SELECT * FROM foo /*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/"
    "/*customer-comment*/ SELECT * FROM foo"                                                      | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "/*customer-comment*/ SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/"
    "/*traceparent"                                                                               | "SqlCommenter" | "Test" | "my-service" | "TestVersion" | false       | null                                                      | "/*traceparent /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion'*/"
  }
}
