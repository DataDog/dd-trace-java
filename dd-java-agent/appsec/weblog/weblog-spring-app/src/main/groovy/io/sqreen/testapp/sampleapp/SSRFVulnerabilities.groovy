package io.sqreen.testapp.sampleapp

import com.google.common.io.Closeables
import de.thetaphi.forbiddenapis.SuppressForbidden
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.HttpMethod
import org.apache.commons.httpclient.methods.GetMethod
import org.apache.http.HttpEntity
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients

@SuppressForbidden
class SSRFVulnerabilities {

  String ssrfHttpUrlConnection(String url) {
    new URL(url).openStream().text
  }

  String ssrfHttpClientJDK11(String url) {
    def httpClient = Class.forName('java.net.http.HttpClient')
    def request = Class.forName('java.net.http.HttpRequest').
      newBuilder().
      uri(URI.create(url)).
      build()
    def bodyHandler = Class.forName('java.net.http.HttpResponse$BodyHandlers').
      ofString()
    def response = httpClient.send(request, bodyHandler)
    response.body
  }

  String ssrfHttpClient4(String url) {
    CloseableHttpClient httpClient = HttpClients.createDefault()
    HttpGet request
    try {
      request = new HttpGet(url)
      CloseableHttpResponse response = httpClient.execute(request)
      HttpEntity entity = response.entity
      entity.content
    } finally {
      Closeables.close(httpClient, true)
    }
  }

  String ssrfHttpClient3(String url) {
    HttpMethod method = new GetMethod(url)
    try {
      method.responseBodyAsString
    } finally {
      method.releaseConnection()
    }
  }
}
