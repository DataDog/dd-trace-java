import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.Request
import okhttp3.Response
import org.apache.jasper.JasperException
import org.eclipse.jetty.http.HttpStatus
import spock.lang.Unroll

class JSPInstrumentationForwardTests extends JSPTestBase {
  @Unroll
  def "non-erroneous GET forward to #forwardTo"() {
    setup:
    String reqUrl = baseUrl + "/$forwardFromFileName"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(5) {
        span {
          parent()
          serviceName jspWebappContext
          operationName "servlet.request"
          resourceName "GET /$jspWebappContext/$forwardFromFileName"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          tags {
            "$Tags.COMPONENT" "java-web-servlet"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/$jspWebappContext/$forwardFromFileName"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "servlet.context" "/$jspWebappContext"
            "servlet.path" "/$forwardFromFileName"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/$forwardFromFileName"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
            defaultTags()
          }
        }
        span {
          childOf span(1)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/$forwardDestFileName"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/$forwardFromFileName"
            "jsp.requestURL" baseUrl + "/$forwardDestFileName"
            defaultTags()
          }
        }
        span {
          childOf span(1)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/$forwardDestFileName"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.$jspForwardDestClassPrefix$jspForwardDestClassName"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/$forwardFromFileName"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.$jspForwardFromClassPrefix$jspForwardFromClassName"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
      }
    }
    res.code() == HttpStatus.OK_200

    cleanup:
    res.close()

    where:
    forwardTo         | forwardFromFileName                | forwardDestFileName | jspForwardFromClassName   | jspForwardFromClassPrefix | jspForwardDestClassName | jspForwardDestClassPrefix
    "no java jsp"     | "forwards/forwardToNoJavaJsp.jsp"  | "nojava.jsp"        | "forwardToNoJavaJsp_jsp"  | "forwards."               | "nojava_jsp"            | ""
    "normal java jsp" | "forwards/forwardToSimpleJava.jsp" | "common/loop.jsp"   | "forwardToSimpleJava_jsp" | "forwards."               | "loop_jsp"              | "common."
  }

  def "non-erroneous GET forward to plain HTML"() {
    setup:
    String reqUrl = baseUrl + "/forwards/forwardToHtml.jsp"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(3) {
        span {
          parent()
          serviceName jspWebappContext
          operationName "servlet.request"
          resourceName "GET /$jspWebappContext/forwards/forwardToHtml.jsp"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          tags {
            "$Tags.COMPONENT" "java-web-servlet"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/$jspWebappContext/forwards/forwardToHtml.jsp"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "servlet.context" "/$jspWebappContext"
            "servlet.path" "/forwards/forwardToHtml.jsp"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/forwards/forwardToHtml.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/forwards/forwardToHtml.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToHtml_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
      }
    }
    res.code() == HttpStatus.OK_200

    cleanup:
    res.close()
  }

  def "non-erroneous GET forwarded to jsp with multiple includes"() {
    setup:
    String reqUrl = baseUrl + "/forwards/forwardToIncludeMulti.jsp"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(9) {
        span {
          parent()
          serviceName jspWebappContext
          operationName "servlet.request"
          resourceName "GET /$jspWebappContext/forwards/forwardToIncludeMulti.jsp"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          tags {
            "$Tags.COMPONENT" "java-web-servlet"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/$jspWebappContext/forwards/forwardToIncludeMulti.jsp"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "servlet.context" "/$jspWebappContext"
            "servlet.path" "/forwards/forwardToIncludeMulti.jsp"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/forwards/forwardToIncludeMulti.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
            defaultTags()
          }
        }
        span {
          childOf span(1)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/includes/includeMulti.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/forwards/forwardToIncludeMulti.jsp"
            "jsp.requestURL" baseUrl + "/includes/includeMulti.jsp"
            defaultTags()
          }
        }
        span {
          childOf span(2)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/common/javaLoopH2.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/forwards/forwardToIncludeMulti.jsp"
            "jsp.requestURL" baseUrl + "/includes/includeMulti.jsp"
            defaultTags()
          }
        }
        span {
          childOf span(2)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/common/javaLoopH2.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.common.javaLoopH2_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
        span {
          childOf span(2)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/common/javaLoopH2.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/forwards/forwardToIncludeMulti.jsp"
            "jsp.requestURL" baseUrl + "/includes/includeMulti.jsp"
            defaultTags()
          }
        }
        span {
          childOf span(2)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/common/javaLoopH2.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.common.javaLoopH2_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
        span {
          childOf span(1)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/includes/includeMulti.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.includes.includeMulti_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/forwards/forwardToIncludeMulti.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToIncludeMulti_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
      }
    }
    res.code() == HttpStatus.OK_200

    cleanup:
    res.close()
  }

  def "non-erroneous GET forward to another forward (2 forwards)"() {
    setup:
    String reqUrl = baseUrl + "/forwards/forwardToJspForward.jsp"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(7) {
        span {
          parent()
          serviceName jspWebappContext
          operationName "servlet.request"
          resourceName "GET /$jspWebappContext/forwards/forwardToJspForward.jsp"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          tags {
            "$Tags.COMPONENT" "java-web-servlet"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/$jspWebappContext/forwards/forwardToJspForward.jsp"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "servlet.context" "/$jspWebappContext"
            "servlet.path" "/forwards/forwardToJspForward.jsp"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/forwards/forwardToJspForward.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
            defaultTags()
          }
        }
        span {
          childOf span(1)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/forwards/forwardToSimpleJava.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/forwards/forwardToJspForward.jsp"
            "jsp.requestURL" baseUrl + "/forwards/forwardToSimpleJava.jsp"
            defaultTags()
          }
        }
        span {
          childOf span(2)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/common/loop.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.forwardOrigin" "/forwards/forwardToJspForward.jsp"
            "jsp.requestURL" baseUrl + "/common/loop.jsp"
            defaultTags()
          }
        }
        span {
          childOf span(2)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/common/loop.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.common.loop_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
        span {
          childOf span(1)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/forwards/forwardToSimpleJava.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToSimpleJava_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/forwards/forwardToJspForward.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToJspForward_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
      }
    }
    res.code() == HttpStatus.OK_200

    cleanup:
    res.close()
  }

  def "forward to jsp with compile error should not produce a 2nd render span"() {
    setup:
    String reqUrl = baseUrl + "/forwards/forwardToCompileError.jsp"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(4) {
        span {
          parent()
          serviceName jspWebappContext
          operationName "servlet.request"
          resourceName "GET /$jspWebappContext/forwards/forwardToCompileError.jsp"
          spanType DDSpanTypes.HTTP_SERVER
          errored true
          tags {
            "$Tags.COMPONENT" "java-web-servlet"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/$jspWebappContext/forwards/forwardToCompileError.jsp"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 500
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "servlet.context" "/$jspWebappContext"
            "servlet.path" "/forwards/forwardToCompileError.jsp"
            errorTags(JasperException, String)
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/forwards/forwardToCompileError.jsp"
          errored true
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
            errorTags(JasperException, String)
            defaultTags()
          }
        }
        span {
          childOf span(1)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/compileError.jsp"
          errored true
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.compileError_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            errorTags(JasperException, String)
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/forwards/forwardToCompileError.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToCompileError_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
      }
    }
    res.code() == HttpStatus.INTERNAL_SERVER_ERROR_500

    cleanup:
    res.close()
  }

  def "forward to non existent jsp should be 404"() {
    setup:
    String reqUrl = baseUrl + "/forwards/forwardToNonExistent.jsp"
    Request req = new Request.Builder().url(new URL(reqUrl)).get().build()

    when:
    Response res = client.newCall(req).execute()

    then:
    assertTraces(1) {
      trace(3) {
        span {
          parent()
          serviceName jspWebappContext
          operationName "servlet.request"
          resourceName "404"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          tags {
            "$Tags.COMPONENT" "java-web-servlet"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/$jspWebappContext/forwards/forwardToNonExistent.jsp"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 404
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "servlet.context" "/$jspWebappContext"
            "servlet.path" "/forwards/forwardToNonExistent.jsp"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.render"
          resourceName "/forwards/forwardToNonExistent.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.requestURL" reqUrl
            defaultTags()
          }
        }
        span {
          childOf span(0)
          serviceName jspWebappContext
          operationName "jsp.compile"
          resourceName "/forwards/forwardToNonExistent.jsp"
          errored false
          tags {
            "$Tags.COMPONENT" "jsp-http-servlet"
            "servlet.context" "/$jspWebappContext"
            "jsp.classFQCN" "org.apache.jsp.forwards.forwardToNonExistent_jsp"
            "jsp.compiler" "org.apache.jasper.compiler.JDTCompiler"
            defaultTags()
          }
        }
      }
    }
    res.code() == HttpStatus.NOT_FOUND_404

    cleanup:
    res.close()
  }
}
