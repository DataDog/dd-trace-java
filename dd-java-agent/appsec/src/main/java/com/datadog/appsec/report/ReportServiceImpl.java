package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.dtos.intake.IntakeBatch;
import com.datadog.appsec.report.raw.events.attack.Attack010;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReportServiceImpl implements ReportService {

  private static final Logger log = LoggerFactory.getLogger(ReportServiceImpl.class);

  private final AppSecApi api;
  private final ReportStrategy strategy;
  private List<Object> events = new ArrayList<>();

  public ReportServiceImpl(AppSecApi api, ReportStrategy strategy) {
    this.api = api;
    this.strategy = strategy;
  }

  @Override
  public void reportAttack(Attack010 attack) {
    synchronized (this) {
      events.add(attack);
    }
    if (strategy.shouldFlush(attack)) {
      flush();
    }
  }

  private void flush() {
    List<Object> oldEvents;
    synchronized (this) {
      oldEvents = events;
      events = new ArrayList<>();
    }
    log.debug("About to flush {} events", oldEvents.size());

    IntakeBatch batch =
        new IntakeBatch.IntakeBatchBuilder().withProtocolVersion(1).withEvents(oldEvents).build();

    this.api.sendIntakeBatch(batch, ReportSerializer.getIntakeBatchAdapter());
  }
}
