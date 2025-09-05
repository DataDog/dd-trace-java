package server

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.communication.util.IOUtils
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.OkHttpUtils
import okhttp3.MultipartBody
import okhttp3.Request
import spock.lang.Shared

/* Don't actually need AgentTestRunner, but it messes up the classloader for AgentTestRunnerTest if this runs first. */

class HttpServerTest extends InstrumentationSpecification {
  @Shared
  def client = OkHttpUtils.client()

  def "test server lifecycle"() {
    setup:
    def server = httpServer {
      handlers {}
    }

    expect:
    server.internalServer.isRunning()

    when:
    server.start()

    then:
    server.internalServer.isRunning()

    when:
    server.stop()

    then:
    !server.internalServer.isRunning()

    when:
    server.start()

    then:
    server.internalServer.isRunning()

    when:
    server.close()

    then:
    !server.internalServer.isRunning()

    cleanup:
    server.close()
  }

  def "server 404's with no handlers"() {
    setup:
    def server = httpServer {
      handlers {}
    }

    when:
    def request = new Request.Builder()
      .url("$server.address")
      .get()
      .build()
    def clientResponse = client.newCall(request).execute()

    then:
    clientResponse.code() == 404
    clientResponse.body().string().contains("<title>Error 404")

    cleanup:
    server.stop()
  }

  def "server accepts #path requests"() {
    setup:
    def server = httpServer {
      handlers {
        get("/get") {
          response.send("/get response")
        }
        post("/post") {
          response.send("/post response")
        }
        put("/put") {
          response.send("/put response")
        }
        prefix("/base") {
          response.send("${request.path} response")
        }
        all {
          response.send("/all response")
        }
      }
    }

    when:
    def clientRequest = new Request.Builder()
      .url("$server.address/$path")

    if (method == "get") {
      clientRequest."$method"()
    } else {
      clientRequest."$method"(body())
    }

    def clientResponse = client.newCall(clientRequest.build()).execute()

    then:
    clientResponse.code() == 200
    clientResponse.body().string().trim() == "/$path response"

    cleanup:
    server.stop()

    where:
    method | path
    "get"  | "get"
    "post" | "post"
    "put"  | "put"
    "get"  | "base"
    "post" | "base"
    "get"  | "base/get"
    "post" | "base/post"
    "get"  | "all"
    "post" | "all"
  }

  def "server returns different response codes"() {
    setup:
    def server = httpServer {
      handlers {
        get("/get") {
          response.status(201).send("get response")
        }
        post("/post") {
          response.status(202).send("post response")
        }
        all {
          response.status(203).send("all response")
        }
      }
    }

    when:
    def request = new Request.Builder()
      .url("$server.address/$method")

    if (method == "post") {
      request."$method"(body())
    } else {
      request."$method"()
    }

    def clientResponse = client.newCall(request.build()).execute()

    then:
    clientResponse.code() == code
    clientResponse.body().string().trim() == "$resp"
    server.lastRequest.contentType == contentType
    server.lastRequest.text.empty == !hasContent

    cleanup:
    server.stop()

    where:
    method | resp            | code | contentType       | hasContent
    "get"  | "get response"  | 201  | null              | false
    "post" | "post response" | 202  | "multipart/mixed" | true
    "head" | ""              | 203  | null              | false
  }

  def "server retains details of request sent"() {
    setup:
    def server = httpServer {
      handlers {
        all {
          response.send()
        }
      }
    }

    when:
    def request = new Request.Builder()
      .url("$server.address")
      .get()
      .header(headerKey, headerValue)
      .build()

    def clientResponse = client.newCall(request).execute()

    then:
    clientResponse.code() == 200
    clientResponse.body().string().trim() == ""

    server.lastRequest.contentType == null
    server.lastRequest.headers.get(headerKey) == headerValue
    server.lastRequest.headers.get(newKey) == null

    when:
    def request2 = new Request.Builder()
      .url("$server.address")
      .get()
      .header(newKey, newValue)
      .build()

    def response2 = client.newCall(request2).execute()

    then:
    response2.code() == 200
    response2.body().string().trim() == ""

    server.lastRequest.contentType == null
    server.lastRequest.headers.get(headerKey) == null
    server.lastRequest.headers.get(newKey) == newValue

    cleanup:
    server.stop()

    where:
    headerKey = "some-key"
    headerValue = "some-value"
    newKey = "new-key"
    newValue = "new-value"
  }

  def "server redirect"() {
    setup:
    client = OkHttpUtils.client(followRedirects)
    def server = httpServer {
      handlers {
        get("/redirect") {
          redirect("/redirected")
        }
        get("/redirected") {
          response.status(201).send("somewhere else")
        }
      }
    }

    when:
    def request = new Request.Builder()
      .url("$server.address/redirect")
      .get()
      .build()
    def clientResponse = client.newCall(request).execute()

    then:
    clientResponse.code() == code
    clientResponse.body().string() == body

    cleanup:
    server.stop()

    where:
    followRedirects | code | body
    true            | 201  | "somewhere else"
    false           | 302  | ""
  }

  def "server sends no body on head request"() {
    setup:
    def server = httpServer {
      handlers {
        all {
          response.send("invalid content")
        }
      }
    }

    when:
    def request = new Request.Builder()
      .url("$server.address")
      .head()
      .build()

    def clientResponse = client.newCall(request).execute()

    then:
    clientResponse.code() == 200
    clientResponse.body().string().trim() == ""

    cleanup:
    server.stop()
  }

  def "server handles distributed request"() {
    setup:
    def server = httpServer {
      handlers {
        all {
          handleDistributedRequest()
          response.send("done")
        }
      }
    }

    when:
    def request = new Request.Builder()
      .url("$server.address")
      .get()
      .build()

    def clientResponse = client.newCall(request).execute()

    then:
    clientResponse.code() == 200
    clientResponse.body().string().trim() == "done"

    assertTraces(1) {
      server.distributedRequestTrace(it)
    }

    cleanup:
    server.stop()
  }

  def "server ignores distributed request when header set"() {
    setup:
    def server = httpServer {
      handlers {
        all {
          handleDistributedRequest()
          response.send("done")
        }
      }
    }

    when:
    def request = new Request.Builder()
      .url("$server.address")
      .header("is-dd-server", "false")
      .get()
      .build()

    def clientResponse = runUnderTrace("parent") {
      client.newCall(request).execute()
    }

    then:
    clientResponse.code() == 200
    clientResponse.body().string().trim() == "done"

    assertTraces(1) {
      trace(1) {
        basicSpan(it, "parent")
      }
    }

    cleanup:
    server.stop()
  }

  def "server handles distributed request when header set"() {
    setup:
    def server = httpServer {
      handlers {
        all {
          handleDistributedRequest()
          response.send("done")
        }
      }
    }

    when:
    def request = new Request.Builder()
      .url("$server.address")
      .header("is-dd-server", "true")
      .get()
      .build()

    def clientResponse = client.newCall(request).execute()

    then:
    clientResponse.code() == 200
    clientResponse.body().string().trim() == "done"

    // parent<->child relation can't be tested because okhttp isnt traced here
    assertTraces(1) {
      server.distributedRequestTrace(it)
    }

    cleanup:
    server.stop()
  }


  def "calling send() twice is an error"() {
    setup:
    def server = httpServer {
      handlers {
        all {
          response.send()
          response.send()
        }
      }
    }

    when:
    def request = new Request.Builder()
      .url("$server.address")
      .get()
      .build()

    def clientResponse = client.newCall(request).execute()

    then:
    clientResponse.code() == 500
    IOUtils.readFully (clientResponse.body().byteStream()).contains("assert !req.orig.handled")

    cleanup:
    server.stop()
  }

  def "calling send() with null is an error"() {
    setup:
    def server = httpServer {
      handlers {
        all {
          response.send((byte[]) null)
        }
      }
    }

    when:
    def request = new Request.Builder()
      .url("$server.address")
      .get()
      .build()

    def clientResponse = client.newCall(request).execute()

    then:
    clientResponse.code() == 500
    IOUtils.readFully (clientResponse.body().byteStream()).contains("assert body != null")

    cleanup:
    server.stop()
  }

  def body() {
    return new MultipartBody.Builder().addFormDataPart("key", "value").build()
  }
}
