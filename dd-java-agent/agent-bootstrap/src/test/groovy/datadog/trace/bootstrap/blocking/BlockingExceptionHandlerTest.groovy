package datadog.trace.bootstrap.blocking

import datadog.appsec.api.blocking.BlockingException
import spock.lang.Specification

class BlockingExceptionHandlerTest extends Specification {

  void 'test blocking exception handler'() {

    when:
    BlockingExceptionHandler.rethrowIfBlockingException(new BlockingException())

    then:
    thrown(BlockingException)

    when:
    def result = BlockingExceptionHandler.rethrowIfBlockingException(new UnsupportedOperationException())

    then:
    noExceptionThrown()
    result instanceof UnsupportedOperationException
  }
}
