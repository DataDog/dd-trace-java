package io.sqreen.testapp.sampleapp


import groovy.transform.CompileStatic
import io.sqreen.sasdk.backend.IngestionHttpClientBuilder
import io.sqreen.sasdk.signals_dto.Actor
import io.sqreen.sasdk.signals_dto.PointSignal
import io.sqreen.sasdk.signals_dto.context.http.HttpContext
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.servlet.http.HttpServletRequest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

@RestController
@RequestMapping('/sa-sdk')
class SaSdkController {

  static class SdkCallPaySpec {
    String ingestionUrl = 'https://ingestion.sqreen.com/'
    String proxy
    String event
    String login
    String propertyName
    String propertyValue
    String timestamp
  }

  @RequestMapping('/event')
  @CompileStatic
  String signup(SdkCallPaySpec spec, HttpServletRequest req) {
    def sig = new PointSignal(
      name: "sq.sdk.$spec.event",
      source: 'sqreen:sdk:track',
      time: spec.timestamp ? new Date(Long.parseLong(spec.timestamp)) : new Date(),
      actor: [
        ipAddresses: ['::1'],
        userAgent  : req.getHeader('User-Agent'),
      ] as Actor,
      contextSchema: 'http/2020-01-01T00:00:00.000Z',
      context: [
        request: [
          headers: [:],
          parameters: [:],
          path: req.requestURI,
          port: req.localPort,
          remote_ip: req.remoteAddr,
          remote_port: req.remotePort,
          scheme: req.secure ? 'https' : 'http',
          verb: req.method,
        ],
        response: []] as HttpContext,
      payloadSchema : 'track_event/2020-01-01T00:00:00.000Z',
      payload: [
        properties: [
          (spec.propertyName): spec.propertyValue,
        ],
        user_identifiers: [
          login: spec.login,
        ]
      ] as Map<String, Object>
      )

    SSLContext sslContext = SSLContext.getInstance('SSL')
    sslContext.init(
      null, [NaiveTrustManager.INSTANCE] as TrustManager[],
      new SecureRandom())

    def client = new IngestionHttpClientBuilder()
      .withAlternativeIngestionURL(spec.ingestionUrl)
      .buildingHttpClient()
      .withProxy(spec.proxy)
      .withConnectionSocketFactory(new SSLConnectionSocketFactory(sslContext))
      .buildHttpClient()
      .createWithAgentAuthentication()

    client.reportSignal(sig)

    """track event $spec.event with login = $spec.login,
        properties = { $spec.propertyName = $spec.propertyValue }
        at timestamp = $spec.timestamp"""
  }

  static final class NaiveTrustManager implements X509TrustManager {
    private NaiveTrustManager() {
    }

    static final NaiveTrustManager INSTANCE = new NaiveTrustManager()

    @Override
    void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
      // purposefully left blank
    }

    @Override
    void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
      // purposefully left blank
    }

    @Override
    X509Certificate[] getAcceptedIssuers() {
      [] as X509Certificate[]
    }
  }
}
