package io.sqreen.testapp.sampleapp

import com.google.common.base.Charsets
import groovy.transform.CompileStatic
import io.sqreen.testapp.imitation.VulnerableExecutions
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.User
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
@RequestMapping(produces = 'text/plain')
class AsyncController {

  @RequestMapping('/asyncContext')
  @CompileStatic
  def asyncContext(HttpServletRequest req, HttpServletResponse resp,
    @RequestParam(defaultValue = 'inline') String strategy,
    @RequestParam(defaultValue = '') String eval) {
    HttpServletRequest wrappedReq = new HttpServletRequestWrapper(req)
    HttpServletResponse wrappedResp = new HttpServletResponseWrapper(resp)

    if (strategy == 'spring')  {
      return doSpringVariant(eval)
    }

    def async = req.startAsync(wrappedReq, wrappedResp)
    async.setTimeout(3000)

    Runnable runnable
    if (eval != '') {
      // glassfish 4 needs the stream to be fetch only once
      // under penalty of only one thread being able to write
      def stream = async.response.outputStream
      runnable = {
        String res = VulnerableExecutions.eval(eval, 'eval_reader', this)
        synchronized (stream) {
          stream.write res.getBytes(Charsets.UTF_8)
          stream.flush()
        }
      } as Runnable
    } else {
      runnable = {
        async.response.outputStream.write(
          "$message from ${Thread.currentThread().name}".getBytes(Charsets.UTF_8))
      } as Runnable
    }
    Runnable runnableWithComplete = wrapWithComplete(async, runnable)

    switch (strategy) {
      case 'inline':
        runnableWithComplete.run()
        break
      case 'thread':
        Thread.start cl(runnableWithComplete)
        break
      case 'multiple_threads':
        Thread t1 = Thread.start cl(runnable)
        Thread t2 = Thread.start cl(runnable)
        Thread.start {
          [t1, t2]*.join()
          async.complete()
        }
        break
      case 'async_start':
        async.start runnableWithComplete
        break
      case 'async_start_no_complete':
      // WILL RESULT IN DISPATCH LOOP
        async.start runnable
        break
      case 'async_restart':
        async.dispatch('/asyncContext?strategy=_async_restart_1&eval='
        + URLEncoder.encode(eval, 'UTF-8'))
        break
      case ~/.*_async_restart_1.*/:
        async.start {
          async.start {
            try {
              runnable.run()
            }
            finally {
              async.complete()
            }
          }
        }
        break
      case 'dispatch_inline':
        async.dispatch('/eval?q=' + URLEncoder.encode(eval, 'UTF-8'))
        break
      case 'dispatch_thread':
        Thread.start { async.dispatch('/eval?q=' + URLEncoder.encode(eval, 'UTF-8')) }
        break
      case 'dispatch_timeout':
        async.timeout = 200
        async.addListener(new AbstractAsyncListener() {
            @Override
            void onTimeout(AsyncEvent event) throws IOException {
              event.asyncContext.dispatch('/eval?q=' + URLEncoder.encode(eval, 'UTF-8'))
            }
          })
        break
      case 'exception_before_return':
      // this can result in either a jetty error page or a sqreen error page
      // being shown, depending on the timing
        async.start runnableWithComplete
        throw new RuntimeException("exception_before_return throws")
      case 'exception_before_return_delay':
        async.start { -> sleep 250; runnableWithComplete.run() }
        throw new RuntimeException("exception_before_return_delay throws")
      case 'dispatch_to_error':
        async.dispatch('/hello/error')
        break
    }
    null
  }

  private Runnable wrapWithComplete(AsyncContext asyncContext, Runnable r) {
    {
      ->
      try {
        r.run()
      } finally {
        asyncContext.complete()
      }
    }
  }
  private Closure cl(Runnable r) {
    {
      ->
      r.run()
    }
  }

  private Callable<String> doSpringVariant(String eval) {
    if (eval != '') {
      {
        ->
        VulnerableExecutions.eval(eval, 'eval_reader', this)
      } as Callable<String>
    } else {
      {
        ->
        "$message from ${Thread.currentThread().name}"
      } as Callable<String>
    }
  }

  static abstract class AbstractAsyncListener implements AsyncListener {
    @Override
    void onComplete(AsyncEvent event) throws IOException {}

    @Override
    void onTimeout(AsyncEvent event) throws IOException {}

    @Override
    void onError(AsyncEvent event) throws IOException {}

    @Override
    void onStartAsync(AsyncEvent event) throws IOException {}
  }

  @RequestMapping('/secret')
  String secret(@AuthenticationPrincipal User user) {
    "Secret $message (to ${user.username})"
  }

  static String getMessage() {
    'Hello world!'
  }

}
