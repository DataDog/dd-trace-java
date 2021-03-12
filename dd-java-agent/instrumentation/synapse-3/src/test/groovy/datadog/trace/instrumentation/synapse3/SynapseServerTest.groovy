package datadog.trace.instrumentation.synapse3

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.apache.synapse.ServerConfigurationInformation
import org.apache.synapse.ServerContextInformation
import org.apache.synapse.ServerManager
import spock.lang.Requires
import spock.lang.Shared

import static datadog.trace.api.Platform.isJavaVersionAtLeast
import static datadog.trace.instrumentation.synapse3.TestPassThroughHttpListener.PORT

@Requires({
  isJavaVersionAtLeast(8)
})
class SynapseServerTest extends AgentTestRunner {

  String expectedServiceName() {
    CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
  }

  @Shared
  ServerManager server

  @Shared
  OkHttpClient client

  def setupSpec() {
    def testResourceDir = System.getProperty('user.dir') + '/src/test/resources/'
    ServerConfigurationInformation config = new ServerConfigurationInformation()

    config.setSynapseHome(testResourceDir + 'synapse/')
    config.setAxis2RepoLocation(testResourceDir + 'synapse/repository/')
    config.setSynapseXMLLocation(testResourceDir + 'synapse/repository/conf/synapse.xml')
    config.setAxis2Xml(testResourceDir + 'synapse/repository/conf/axis2.xml')

    server = new ServerManager()
    server.init(config, new ServerContextInformation(config))
    server.start()

    client = OkHttpUtils.client()
  }

  def cleanupSpec() {
    server.shutdown()

    // cleanup stray access logs - unfortunately Synapse won't let us choose where these go
    def accessLogDir = new File(System.getProperty('user.dir') + '/logs')
    if (accessLogDir.isDirectory()) {
      accessLogDir.eachFileMatch(~/http_access_[0-9-]*.log/, { it.delete() })
      accessLogDir.delete()
    }
  }

  def "test plain request is traced"() {
    setup:
    def request = new Request.Builder()
      .url("http://127.0.0.1:${PORT}/services/SimpleStockQuoteService?wsdl")
      .get()
      .build()

    when:
    int statusCode = client.newCall(request).execute().code()

    then:
    assertTraces(1) {
      trace(1) {
        httpSpan(it, 0, 'GET', statusCode)
      }
    }
    statusCode == 200
  }

  def "test passthru request is traced"() {
    setup:
    def request = new Request.Builder()
      .url("http://127.0.0.1:${PORT}/services/SimpleStockQuoteService")
      .header('Content-Type', 'text/xml')
      .header('WSAction', 'urn:getRandomQuote')
      .header('SOAPAction', 'urn:getRandomQuote')
      .post(RequestBody.create(MediaType.get('text/xml'), '''
          <s11:Envelope xmlns:s11='http://schemas.xmlsoap.org/soap/envelope/'>
            <s11:Body/>
          </s11:Envelope>'''))
      .build()

    when:
    int statusCode = client.newCall(request).execute().code()

    then:
    assertTraces(1) {
      trace(1) {
        httpSpan(it, 0, 'POST', statusCode)
      }
    }
    statusCode == 200
  }

  def "test error status is captured"() {
    setup:
    def request = new Request.Builder()
      .url("http://127.0.0.1:${PORT}/services/SimpleStockQuoteService")
      .header('Content-Type', 'text/xml')
      .header('WSAction', 'urn:getQuoteError')
      .header('SOAPAction', 'urn:getQuoteError')
      .post(RequestBody.create(MediaType.get('text/xml'), '''
          <s11:Envelope xmlns:s11='http://schemas.xmlsoap.org/soap/envelope/'>
            <s11:Body/>
          </s11:Envelope>'''))
      .build()

    when:
    int statusCode = client.newCall(request).execute().code()

    then:
    assertTraces(1) {
      trace(1) {
        httpSpan(it, 0, 'POST', statusCode)
      }
    }
    statusCode == 500
  }

  def httpSpan(TraceAssert trace, int index, String method, int statusCode, Object parentSpan = null) {
    trace.span {
      serviceName expectedServiceName()
      operationName "http.request"
      resourceName "${method} /services/SimpleStockQuoteService"
      spanType DDSpanTypes.HTTP_SERVER
      errored statusCode >= 500
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      topLevel parentSpan == null
      tags {
        "$Tags.COMPONENT" "synapse-server"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" Integer
        "$Tags.HTTP_URL" "/services/SimpleStockQuoteService"
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_STATUS" statusCode
        defaultTags()
      }
    }
  }
}
