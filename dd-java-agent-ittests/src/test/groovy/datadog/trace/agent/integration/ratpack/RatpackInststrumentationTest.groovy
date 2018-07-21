package datadog.trace.agent.integration.ratpack

import datadog.opentracing.DDSpan
import datadog.opentracing.DDTracer
import datadog.trace.agent.test.IntegrationTestUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.common.writer.ListWriter
import io.opentracing.tag.Tags
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.h2.Driver
import ratpack.exec.Blocking
import ratpack.groovy.test.embed.GroovyEmbeddedApp
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

class RatpackInststrumentationTest extends Specification {


  @Shared
  def writer = new ListWriter()
  @Shared
  def tracer = new DDTracer(writer)

  @Shared
  GroovyEmbeddedApp server

  def setupSpec() {
    IntegrationTestUtils.registerOrReplaceGlobalTracer(tracer)
    server = GroovyEmbeddedApp.ratpack {
      handlers {
        get {
          Blocking.get {
            Connection h2Connection = new Driver().connect("jdbc:h2:mem:integ-test", null)
            Statement statement = h2Connection.createStatement()
            ResultSet resultSet = statement.executeQuery('SELECT 1')
            resultSet.next()
            resultSet.getInt(1)
          } then {
            context.render("success${it}")
          }
        }
      }
    }
    server.server.start()
  }

  def cleanupSpec() {
    server.server.stop()
  }

  def setup() {
    writer.clear()
  }

  def "trace request with propagation"() {
    setup:

    final HttpClientBuilder builder = HttpClientBuilder.create()

    final HttpClient client = builder.build()
    IntegrationTestUtils.runUnderTrace("someTrace") {
      try {
        HttpResponse response =
          client.execute(new HttpGet(server.address))
        assert response.getStatusLine().getStatusCode() == 200
      } catch (Exception e) {
        e.printStackTrace()
        throw new RuntimeException(e)
      }
    }
    expect:
    // one trace on the server, one trace on the client
    writer.size() == 2
    final List<DDSpan> serverTrace = writer.get(0)
    serverTrace.size() == 2

    final List<DDSpan> clientTrace = writer.get(1)
    clientTrace.size() == 3
    clientTrace.get(0).getOperationName() == "someTrace"
    // our instrumentation makes 2 spans for apache-httpclient
    final DDSpan localSpan = clientTrace.get(1)
    localSpan.getType() == null
    localSpan.getTags()[Tags.COMPONENT.getKey()] == "apache-httpclient"
    localSpan.getOperationName() == "apache.http"

    final DDSpan clientSpan = clientTrace.get(2)
    clientSpan.getOperationName() == "http.request"
    clientSpan.getType() == DDSpanTypes.HTTP_CLIENT
    clientSpan.getTags()[Tags.HTTP_METHOD.getKey()] == "GET"
    clientSpan.getTags()[Tags.HTTP_STATUS.getKey()] == 200
    clientSpan.getTags()[Tags.HTTP_URL.getKey()] == server.address.toString()
    clientSpan.getTags()[Tags.PEER_HOSTNAME.getKey()] == server.address.host
    clientSpan.getTags()[Tags.PEER_PORT.getKey()] == server.address.port
    clientSpan.getTags()[Tags.SPAN_KIND.getKey()] == Tags.SPAN_KIND_CLIENT

    // ensure that the db query is in the trace
    serverTrace.get(1).operationName == 'h2.query'

    // client trace propagates to server
    clientSpan.getTraceId() == serverTrace.get(0).getTraceId()
    // server span is parented under http client
    clientSpan.getSpanId() == serverTrace.get(0).getParentId()

    serverTrace.get(1).parentId == serverTrace.get(0).spanId
  }
}
