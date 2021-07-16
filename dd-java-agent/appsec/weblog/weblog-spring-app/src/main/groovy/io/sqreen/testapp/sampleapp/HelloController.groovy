package io.sqreen.testapp.sampleapp

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.User
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

import javax.servlet.AsyncContext
import javax.servlet.AsyncEvent
import javax.servlet.AsyncListener
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletResponseWrapper
import java.util.concurrent.Callable

@RestController
@RequestMapping(value = '/hello', produces = 'text/plain')
class HelloController {

  @RequestMapping
  String index() {
    message
  }

  @RequestMapping('/async')
  Callable<String> async() {
    { -> "$message from ${Thread.currentThread().name}" }
  }

  @RequestMapping('/asyncStart')
  void async2(HttpServletRequest request, HttpServletResponse response) {
    HttpServletRequest requestWrapper = new HttpServletRequestWrapper(request)
    HttpServletResponse responseWrapper = new HttpServletResponseWrapper(response)

    AsyncContext async = request.startAsync(requestWrapper, responseWrapper)
    async.start {
      async.response.writer.write "$message from ${Thread.currentThread().name}"
      async.complete()
    }
  }

  @RequestMapping('/asyncFake')
  void asyncFake(HttpServletRequest request, HttpServletResponse response) {
    HttpServletRequest requestWrapper = new HttpServletRequestWrapper(request)
    HttpServletResponse responseWrapper = new HttpServletResponseWrapper(response)

    AsyncContext async = request.startAsync(requestWrapper, responseWrapper)
    async.response.writer.write "$message from ${Thread.currentThread().name}"
    async.complete()
  }

  @RequestMapping('/asyncDispatch')
  void asyncDispatch(HttpServletRequest request, HttpServletResponse response) {
    HttpServletRequest requestWrapper = new HttpServletRequestWrapper(request)
    HttpServletResponse responseWrapper = new HttpServletResponseWrapper(response)

    AsyncContext async = request.startAsync(requestWrapper, responseWrapper)
    String dispatchUrl = request.servletPath.replaceFirst ~/Dispatch$/, ''
    async.dispatch dispatchUrl
  }

  @RequestMapping('/asyncDispatch404')
  void asyncDispatch404(HttpServletRequest request) {
    AsyncContext async = request.startAsync()
    async.dispatch 'does not exist'
  }

  @RequestMapping('/asyncTimeoutDispatch')
  void asyncTimeoutDispatch(HttpServletRequest request) {
    AsyncContext async = request.startAsync()
    async.timeout = 200
    String dispatchUrl = request.servletPath.replaceFirst ~/TimeoutDispatch$/, ''
    async.addListener(new AsyncListener() {
        @Override
        void onComplete(AsyncEvent event) throws IOException {}

        @Override
        void onTimeout(AsyncEvent event) throws IOException {
          event.asyncContext.dispatch(dispatchUrl)
        }

        @Override
        void onError(AsyncEvent event) throws IOException {}

        @Override
        void onStartAsync(AsyncEvent event) throws IOException {}
      })
  }

  @RequestMapping('/secret')
  String secret(@AuthenticationPrincipal User user) {
    "Secret $message (to ${user.username})"
  }

  @RequestMapping('/error')
  void error() {
    throw new RuntimeException("The controller just throws this")
  }

  static String getMessage() {
    'Hello world!'
  }
}
