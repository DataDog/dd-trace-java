package datadog.trace.api.time;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

class Java8TimeSource implements TimeSource {
  @Override
  public long getNanoTicks() {
    return System.nanoTime();
  }

  @Override
  public long getCurrentTimeMillis() {
    return System.currentTimeMillis();
  }

  @Override
  public long getCurrentTimeMicros() {
    return Instant.now().toEpochMilli();
  }

  @Override
  public long getCurrentTimeNanos() {
    Instant now = Instant.now();
    return TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();
  }
}
