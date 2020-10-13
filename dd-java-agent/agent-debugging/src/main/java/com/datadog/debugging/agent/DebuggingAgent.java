package com.datadog.debugging.agent;

import java.lang.instrument.Instrumentation;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DebuggingAgent {

  public static synchronized void run(Instrumentation instrumentation) {
    log.info("starting debugging agent");
  }
}
