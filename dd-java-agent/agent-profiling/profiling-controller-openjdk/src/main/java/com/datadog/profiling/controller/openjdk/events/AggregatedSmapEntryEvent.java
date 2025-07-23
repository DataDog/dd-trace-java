package com.datadog.profiling.controller.openjdk.events;

import java.util.List;
import java.util.stream.Collectors;
import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.AggregatedSmapEntry")
@Label("Aggregated Smap Entry")
@Description("Entry for the entries from the smaps file aggregated by NMT category")
@Category("Datadog")
@StackTrace(false)
public class AggregatedSmapEntryEvent extends Event {
  private static final EventType TYPE = EventType.getEventType(AggregatedSmapEntryEvent.class);

  @Label("NMT Category")
  private final String nmtCategory;

  @Label("Resident Set Size")
  @DataAmount
  private final long rss;

  public AggregatedSmapEntryEvent(String nmtCategory, long rss) {
    this.nmtCategory = nmtCategory;
    this.rss = rss;
  }

  static void emit(List<SmapEntryEvent> events) {
    if (TYPE.isEnabled()) {
      events.stream()
          .collect(Collectors.groupingBy(e -> e.nmtCategory, Collectors.summingLong(e -> e.rss)))
          .forEach(
              (category, totalRss) -> new AggregatedSmapEntryEvent(category, totalRss).commit());
    }
  }
}
