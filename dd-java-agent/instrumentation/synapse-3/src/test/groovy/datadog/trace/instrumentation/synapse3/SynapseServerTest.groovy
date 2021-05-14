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
import org.apache.axis2.description.TransportInDescription
import org.apache.axis2.transport.TransportListener
import org.apache.http.impl.nio.reactor.DefaultListeningIOReactor
import org.apache.http.nio.reactor.ListenerEndpoint
import org.apache.synapse.ServerConfigurationInformation
import org.apache.synapse.ServerContextInformation
import org.apache.synapse.ServerManager
import org.apache.synapse.transport.passthru.PassThroughHttpListener
import spock.lang.Requires
import spock.lang.Shared

import java.lang.reflect.Field

import static datadog.trace.api.Platform.isJavaVersionAtLeast

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

  @Shared
  int port

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

    port = getPort(server)
    assert port > 0

    client = OkHttpUtils.client()
  }

  def getPort(ServerManager server) {
    Map<String, TransportInDescription> trpIns = server.getServerContextInformation().
      getSynapseConfiguration().getAxisConfiguration().getTransportsIn()
    assert trpIns.size() == 1

    TransportListener listener = trpIns.get("http").getReceiver()
    assert listener instanceof PassThroughHttpListener

    Field ioReactorField = PassThroughHttpListener.getDeclaredField("ioReactor")
    ioReactorField.setAccessible(true)
    DefaultListeningIOReactor ioReactor = (DefaultListeningIOReactor)ioReactorField.get(listener)
    Set<ListenerEndpoint> endPoints = ioReactor.getEndpoints()
    assert endPoints.size() == 1

    InetSocketAddress address = (InetSocketAddress)endPoints[0].getAddress()
    return address.getPort()
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
      .url("http://127.0.0.1:${port}/services/SimpleStockQuoteService?wsdl")
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
      .url("http://127.0.0.1:${port}/services/SimpleStockQuoteService")
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
      .url("http://127.0.0.1:${port}/services/SimpleStockQuoteService")
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

  def httpSpan(TraceAssert trace, int index, String method, int httpStatus, Object parentSpan = null) {
    trace.span {
      serviceName expectedServiceName()
      operationName "http.request"
      resourceName "${method} /services/SimpleStockQuoteService"
      spanType DDSpanTypes.HTTP_SERVER
      errored httpStatus >= 500
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      topLevel parentSpan == null
      statusCode httpStatus
      tags {
        "$Tags.COMPONENT" "synapse-server"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.HTTP_URL" "/services/SimpleStockQuoteService"
        "$Tags.HTTP_METHOD" method
        defaultTags()
      }
      metrics {
        "$Tags.PEER_PORT" Integer
        defaultMetrics()
      }
    }
  }
}
