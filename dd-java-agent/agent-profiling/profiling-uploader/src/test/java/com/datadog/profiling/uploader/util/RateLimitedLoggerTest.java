package com.datadog.profiling.uploader.util;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

@ExtendWith(MockitoExtension.class)
public class RateLimitedLoggerTest {

  private final long DELAY = 5;
  private final Exception EXCEPTION = new RuntimeException("bad thing");
  private final Object[] EXPECTED_PARAMS = new Object[] {"message", EXCEPTION};

  @Mock private Logger log;
  @Mock private Supplier<Long> timeSource;

  private RatelimitedLogger rateLimitedLog;

  @BeforeEach
  public void setup() {
    rateLimitedLog = new RatelimitedLogger(log, DELAY, timeSource);
  }

  @Test
  public void testDebug() {
    when(log.isDebugEnabled()).thenReturn(true);

    rateLimitedLog.warn("test {}", "message", EXCEPTION);
    rateLimitedLog.warn("test {}", "message", EXCEPTION);

    verify(log, times(2)).warn("test {}", EXPECTED_PARAMS);
  }

  @Test
  public void testWarningOnce() {
    when(log.isWarnEnabled()).thenReturn(true);
    when(timeSource.get()).thenReturn(DELAY);

    rateLimitedLog.warn("test {}", "message", EXCEPTION);
    rateLimitedLog.warn("test {}", "message", EXCEPTION);

    verify(log).warn("test {} {} (Will not log errors for 5 minutes)", EXPECTED_PARAMS);
  }

  @Test
  public void testWarningTwice() {
    when(log.isWarnEnabled()).thenReturn(true);
    when(timeSource.get()).thenReturn(DELAY, DELAY * 2);

    rateLimitedLog.warn("test {}", "message", EXCEPTION);
    rateLimitedLog.warn("test {}", "message", EXCEPTION);

    verify(log, times(2)).warn("test {} {} (Will not log errors for 5 minutes)", EXPECTED_PARAMS);
  }

  @Test
  public void testNoLogs() {
    rateLimitedLog.warn("test {}", "message", EXCEPTION);

    verify(log, never()).warn(any(), any(Object[].class));
  }
}
