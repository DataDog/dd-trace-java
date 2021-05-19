package datadog.trace.instrumentation.ratpack;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ratpack.exec.Downstream;
import ratpack.exec.Upstream;

/**
 * Upstream represents the source/origin of the value for the promise which is sent to each
 * downstream listener. This wrapper's main responsibility is to wrap the Downstream instance when
 * connect is called.
 */
public class UpstreamWrapper<T> implements Upstream<T> {

  private static final Logger log = LoggerFactory.getLogger(UpstreamWrapper.class);
  private final Upstream<T> delegate;

  private UpstreamWrapper(final Upstream<T> delegate) {
    this.delegate = delegate;
  }

  @Override
  public void connect(Downstream<? super T> downstream) throws Exception {
    delegate.connect(DownstreamWrapper.wrapIfNeeded(downstream, activeSpan()));
  }

  public static <T> Upstream<T> wrapIfNeeded(final Upstream<T> delegate) {
    if (delegate instanceof UpstreamWrapper) {
      return delegate;
    }
    log.debug("Wrapping Upstream task {}", delegate);
    return new UpstreamWrapper<>(delegate);
  }
}
