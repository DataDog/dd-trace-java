package datadog.trace.instrumentation.servlet5

import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import java.lang.reflect.Field
import java.lang.reflect.Modifier

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED_IS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SESSION_ID
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.forPath
import static datadog.trace.agent.test.base.HttpServerTest.controller
import static datadog.trace.agent.test.base.HttpServerTest.IG_RESPONSE_HEADER
import static datadog.trace.agent.test.base.HttpServerTest.IG_RESPONSE_HEADER_VALUE

class TestServlet5 extends HttpServlet {
  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    String path = req.requestURI.substring(req.getContextPath().length())

    HttpServerTest.ServerEndpoint endpoint = forPath(path)
    controller(endpoint) {
      resp.contentType = "text/plain"
      resp.addHeader(IG_RESPONSE_HEADER, IG_RESPONSE_HEADER_VALUE)
      switch (endpoint) {
        case SUCCESS:
          resp.status = endpoint.status
          resp.writer.print(endpoint.body)
          break
        case CREATED:
          resp.status = endpoint.status
          resp.writer.print("${endpoint.body}: ${req.reader.text}")
          break
        case CREATED_IS:
          resp.status = endpoint.status
          def stream = req.inputStream
          resp.writer.print("${endpoint.body}: ${stream.getText('UTF-8')}")
          try {
            Field f = stream.getClass().getField('is')
            def innerStream = f.get(stream)
            def method = innerStream.getClass().getMethod('isFinished')
            if ((method.getModifiers() & Modifier.ABSTRACT) == 0) {
              if (!stream.isFinished()) {
                throw new RuntimeException("Not finished")
              }
            }
          } catch (NoSuchMethodException | NoSuchFieldException mnf) {}
          break
        case FORWARDED:
          resp.status = endpoint.status
          resp.writer.print(req.getHeader("x-forwarded-for"))
          break
        case BODY_MULTIPART:
        case BODY_URLENCODED:
          resp.status = endpoint.status
          resp.writer.print(
            req.parameterMap
            .findAll {
              it.key != 'ignore'
            }
            .collectEntries { [it.key, it.value as List] } as String)
          break
        case QUERY_ENCODED_BOTH:
        case QUERY_ENCODED_QUERY:
        case QUERY_PARAM:
          resp.status = endpoint.status
          resp.writer.print(endpoint.bodyForQuery(req.queryString))
          break
        case USER_BLOCK:
          Blocking.forUser('user-to-block').blockIfMatch()
          break
        case REDIRECT:
          resp.sendRedirect(endpoint.body)
          break
        case ERROR:
          resp.sendError(endpoint.status, endpoint.body)
          break
        case EXCEPTION:
          throw new Exception(endpoint.body)
        case CUSTOM_EXCEPTION:
          throw new InputMismatchException(endpoint.body)
        case SESSION_ID:
          req.getSession(true)
          resp.status = endpoint.status
          resp.writer.print(req.requestedSessionId)
          break
        default:
          resp.status = NOT_FOUND.status
          resp.writer.print(NOT_FOUND.body)
          break
      }
    }
  }
  static HttpServerTest.ServerEndpoint getEndpoint(HttpServletRequest req) {
    String truePath
    if (req.servletPath == "") {
      truePath = req.requestURI - ~'^/[^/]+'
    } else {
      // Most correct would be to get the dispatched path from the request
      // This is not part of the spec varies by implementation so the simplest is just removing
      // "/dispatch"
      truePath = req.servletPath.replace("/dispatch", "")
    }
    return HttpServerTest.ServerEndpoint.forPath(truePath)
  }
  HttpServerTest.ServerEndpoint determineEndpoint(HttpServletRequest req) {
    getEndpoint(req)
  }
}
