import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.BaseHash
import datadog.trace.api.Config
import datadog.trace.api.ProcessTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.jdbc.SQLCommenter

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SQLCommenterTest extends InstrumentationSpecification {

  def "test find first word"() {
    setup:

    when:
    String word = SQLCommenter.getFirstWord(sql)

    then:
    word == firstWord

    where:
    sql               | firstWord
    "SELECT *"        | "SELECT"
    "  { "            | "{"
    "{"               | "{"
    "{call"           | "{call"
    "{ call"          | "{"
    "CALL ( ? )"      | "CALL"
    ""                | ""
    "   "             | ""
  }

  def "test encode Sql Comment"() {
    setup:
    injectSysConfig("dd.service", ddService)
    injectSysConfig("dd.env", ddEnv)
    injectSysConfig("dd.version", ddVersion)

    when:
    String sqlWithComment = SQLCommenter.inject(query, dbService, dbType, host, dbName, traceParent, append)

    then:
    sqlWithComment == expected

    where:
    query                                                                                                         | ddService      | ddEnv  | dbService    | dbType     | host | dbName | ddVersion     | append | traceParent                                               | expected
    "SELECT * FROM foo"                                                                                           | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo;"                                                                                          | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/;"
    "SELECT * FROM foo; \t\n\r"                                                                                   | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/;"
    "SELECT * FROM foo; SELECT * FROM bar"                                                                        | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo; SELECT * FROM bar /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo; SELECT * FROM bar; "                                                                      | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo; SELECT * FROM bar /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/;"
    "SELECT * FROM foo"                                                                                           | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "{call dogshelterProc(?, ?)}"                                                                                 | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "{call dogshelterProc(?, ?)} /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "{call dogshelterProc(?, ?)}"                                                                                 | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "{call dogshelterProc(?, ?)} /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "{call dogshelterProc(?, ?)}"                                                                                 | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "{call dogshelterProc(?, ?)}"
    "CALL dogshelterProc(?, ?)"                                                                                   | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "CALL dogshelterProc(?, ?) /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "CALL dogshelterProc(?, ?)"                                                                                   | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "CALL dogshelterProc(?, ?) /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo"                                                                                           | ""             | "Test" | ""           | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo"                                                                                           | ""             | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo"                                                                                           | "SqlCommenter" | "Test" | "my-service" | "mysql"    | ""   | ""     | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo"                                                                                           | ""             | "Test" | ""           | ""         | "h"  | "n"    | ""            | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*ddh='h',dddb='n',dde='Test',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo"                                                                                           | ""             | ""     | ""           | ""         | "h"  | "n"    | ""            | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*ddh='h',dddb='n',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * FROM foo"                                                                                           | ""             | ""     | ""           | ""         | ""   | ""     | ""            | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "SELECT * FROM foo /*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "SELECT * from FOO -- test query"                                                                             | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "SELECT * from FOO -- test query /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/"
    "SELECT /* customer-comment */ * FROM foo"                                                                    | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "SELECT /* customer-comment */ * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/"
    "SELECT * FROM foo"                                                                                           | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/"
    "SELECT * FROM DUAL"                                                                                          | "SqlCommenter" | "Test" | "my-service" | "oracle"   | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * FROM DUAL /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/"
    "SELECT * FROM sys.tables"                                                                                    | "SqlCommenter" | "Test" | "my-service" | "sqlserver"| "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * FROM sys.tables /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/"
    "SELECT /* customer-comment */ * FROM foo"                                                                    | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT /* customer-comment */ * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/"
    "SELECT * from FOO -- test query"                                                                             | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * from FOO -- test query /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/"
    ""                                                                                                            | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | ""
    "   "                                                                                                         | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "    /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/"
    ""                                                                                                            | "SqlCommenter" | "Test" | "postgres"   | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | ""
    "   "                                                                                                         | "SqlCommenter" | "Test" | "postgres"   | "mysql"    | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "    /*ddps='SqlCommenter',dddbs='postgres',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/"
    "SELECT * FROM foo /*dddbs='my-service',ddh='h',dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/" | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * FROM foo /*dddbs='my-service',ddh='h',dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"
    "SELECT * FROM foo /*ddh='h',dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"                    | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * FROM foo /*ddh='h',dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"
    "SELECT * FROM foo /*dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"                            | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * FROM foo /*dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"
    "SELECT * FROM foo /*dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"                                     | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * FROM foo /*dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/"
    "SELECT * FROM foo /*ddps='SqlCommenter',ddpv='TestVersion'*/"                                                | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * FROM foo /*ddps='SqlCommenter',ddpv='TestVersion'*/"
    "SELECT * FROM foo /*ddpv='TestVersion'*/"                                                                    | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * FROM foo /*ddpv='TestVersion'*/"
    "/*ddjk its a customer */ SELECT * FROM foo"                                                                  | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "/*ddjk its a customer */ SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/"
    "SELECT * FROM foo /*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/"                 | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "SELECT * FROM foo /*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/"
    "/*customer-comment*/ SELECT * FROM foo"                                                                      | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "/*customer-comment*/ SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/"
    "/*traceparent"                                                                                               | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | true   | null                                                      | "/*traceparent /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/"
    "SELECT * FROM foo"                                                                                           | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "/*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/ SELECT * FROM foo"
    "SELECT * FROM foo"                                                                                           | ""             | "Test" | ""           | "mysql"    | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "/*ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/ SELECT * FROM foo"
    "SELECT * FROM foo"                                                                                           | ""             | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "/*dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/ SELECT * FROM foo"
    "SELECT * FROM foo"                                                                                           | ""             | "Test" | ""           | ""         | "h"  | "n"    | ""            | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "/*ddh='h',dddb='n',dde='Test',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/ SELECT * FROM foo"
    "SELECT * FROM foo"                                                                                           | ""             | ""     | ""           | ""         | ""   | ""     | ""            | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "/*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/ SELECT * FROM foo"
    "SELECT * from FOO -- test query"                                                                             | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "/*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * from FOO -- test query"
    "SELECT /* customer-comment */ * FROM foo"                                                                    | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "/*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT /* customer-comment */ * FROM foo"
    "SELECT * FROM foo"                                                                                           | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/ SELECT * FROM foo"
    "SELECT /* customer-comment */ * FROM foo"                                                                    | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/ SELECT /* customer-comment */ * FROM foo"
    "SELECT * from FOO -- test query"                                                                             | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/ SELECT * from FOO -- test query"
    ""                                                                                                            | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | ""
    "   "                                                                                                         | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-01" | "/*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/    "
    "/*dddbs='my-service',ddh='h',dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo" | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*dddbs='my-service',ddh='h',dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*ddh='h',dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"                    | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*ddh='h',dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"                            | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*dddb='n',dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"                                     | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*dde='Test',ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"                                                | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*ddps='SqlCommenter',ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*ddpv='TestVersion'*/ SELECT * FROM foo"                                                                    | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*ddpv='TestVersion'*/ SELECT * FROM foo"
    "/*ddjk its a customer */ SELECT * FROM foo"                                                                  | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/ /*ddjk its a customer */ SELECT * FROM foo"
    "/*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * FROM foo"                 | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/ SELECT * FROM foo"
    "/*customer-comment*/ SELECT * FROM foo"                                                                      | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/ /*customer-comment*/ SELECT * FROM foo"
    "/*traceparent"                                                                                               | "SqlCommenter" | "Test" | "my-service" | "mysql"    | "h"  | "n"    | "TestVersion" | false  | null                                                      | "/*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion'*/ /*traceparent"
    "SELECT /*+ SeqScan(foo) */ * FROM foo"                                                                       | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" |  "SELECT /*+ SeqScan(foo) */ * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "/*+ SeqScan(foo) */ SELECT * FROM foo"                                                                       | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" |  "/*+ SeqScan(foo) */ SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "/*+ SeqScan(foo) */ SELECT * FROM foo"                                                                       | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" |  "/*+ SeqScan(foo) */ SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "/*+ SeqScan(foo) */ SELECT * FROM foo"                                                                       | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" |  "/*+ SeqScan(foo) */ SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "/*+ SeqScan(foo) */ SELECT * FROM foo /*ddps=''*/"                                                           | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" |  "/*+ SeqScan(foo) */ SELECT * FROM foo /*ddps=''*/"
    "/*+ SeqScan(foo) */ SELECT * FROM foo /*ddps=''*/"                                                           | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" |  "/*+ SeqScan(foo) */ SELECT * FROM foo /*ddps=''*/"
    "CALL dogshelterProc(?, ?) /*ddps=''*/"                                                                       | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | false  | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "CALL dogshelterProc(?, ?) /*ddps=''*/"
    "CALL dogshelterProc(?, ?) /*ddps=''*/"                                                                       | "SqlCommenter" | "Test" | "my-service" | "postgres" | "h"  | "n"    | "TestVersion" | true   | "00-00000000000000007fffffffffffffff-000000024cb016ea-00" | "CALL dogshelterProc(?, ?) /*ddps=''*/"
  }

  def "inject base hash"() {
    setup:
    injectSysConfig("dd.service", srv)
    injectSysConfig("dd.env", "")
    injectSysConfig("dbm.inject.sql.basehash", Boolean.toString(injectHash))
    injectSysConfig("dd.experimental.propagate.process.tags.enabled", Boolean.toString(processTagsEnabled))
    ProcessTags.reset()
    BaseHash.updateBaseHash(baseHash)

    expect:
    Config.get().isExperimentalPropagateProcessTagsEnabled() == processTagsEnabled
    and:
    SQLCommenter.inject(query, "", "", "", "", "", false) == result

    where:
    query                                      | injectHash | baseHash | processTagsEnabled | srv   | result
    "SELECT *"                                 | true       | 234563   | false              | ""    | "SELECT *"
    "SELECT *"                                 | true       | 234563   | true               | ""    | "/*ddsh='234563'*/ SELECT *"
    "SELECT *"                                 | true       | 345342   | false              | ""    | "SELECT *"
    "SELECT *"                                 | true       | 345342   | true               | ""    | "/*ddsh='345342'*/ SELECT *"
    "SELECT *"                                 | true       | 234563   | false              | "srv" | "/*ddps='srv'*/ SELECT *"
    "SELECT *"                                 | true       | 234563   | true               | "srv" | "/*ddps='srv',ddsh='234563'*/ SELECT *"
    "SELECT *"                                 | true       | 345342   | false              | "srv" | "/*ddps='srv'*/ SELECT *"
    "SELECT *"                                 | true       | 345342   | true               | "srv" | "/*ddps='srv',ddsh='345342'*/ SELECT *"
    "SELECT *"                                 | false      | 234563   | true               | ""    | "SELECT *"
    "SELECT *"                                 | false      | 234563   | true               | "srv" | "/*ddps='srv'*/ SELECT *"
    "SELECT *"                                 | false      | 345342   | true               | "srv" | "/*ddps='srv'*/ SELECT *"
    "/*ddsh='-3750763034362895579'*/ SELECT *" | true       | 234563   | true               | ""    | "/*ddsh='-3750763034362895579'*/ SELECT *"
  }

  def "test encode Sql Comment with peer service"() {
    setup:
    injectSysConfig("dd.service", "SqlCommenter")
    injectSysConfig("dd.env", "Test")
    injectSysConfig("dd.version", "TestVersion")

    when:
    String sqlWithComment = runUnderTrace("testTrace") {
      AgentSpan currSpan = AgentTracer.activeSpan()
      currSpan.setTag(Tags.PEER_SERVICE, peerService)
      return SQLCommenter.inject("SELECT * FROM foo", "my-service", dbType, "h", "n", "00-00000000000000007fffffffffffffff-000000024cb016ea-00", true)
    }

    then:
    sqlWithComment == expected

    where:
    dbType     | peerService | expected
    "mysql"    | null        | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "postgres" | ""          | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
    "postgres" | "testPeer"  | "SELECT * FROM foo /*ddps='SqlCommenter',dddbs='my-service',ddh='h',dddb='n',ddprs='testPeer',dde='Test',ddpv='TestVersion',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/"
  }
}
