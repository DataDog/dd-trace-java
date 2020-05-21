package com.datadog.profiling.mlt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.item.Attribute;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

public class SamplerWriterTest {
  @Test
  public void testSamplerEvent() throws Exception {
    Path target = Paths.get("/tmp", "sampler.jfr");

    ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    SamplerWriter writer = new SamplerWriter();

    for (int j = 0; j < 4; j++) {
      for (int i = 0; i < 15; i++) {
        for (ThreadInfo ti : bean.dumpAllThreads(false, false)) {
          writer.writeThreadSample(ti);
        }
        Thread.sleep(ThreadLocalRandom.current().nextLong(20L) + 5);
      }

      writer.dump(target);

      // sanity check to make sure the recording is loaded
      IItemCollection events = JfrLoaderToolkit.loadEvents(target.toFile());
      assertTrue(events.apply(ItemFilters.type(SamplerWriter.SAMPLER_EVENT_NAME)).hasItems());
    }
  }

  @Test
  void checkPackageName() {
    assertEquals("", SamplerWriter.getPackageName("Nopackage"));
    assertEquals("package", SamplerWriter.getPackageName("package.Class"));
  }

  @Test
  void testContextEvent() throws Exception {
    String traceid = "0x123456789";
    Path target = Paths.get("/tmp", "context.jfr");

    SamplerWriter writer = new SamplerWriter();
    writer.writeContextEvent(SamplerContext.builder().traceId(traceid).build());

    writer.dump(target);

    // sanity check to make sure the recording is loaded
    IItemCollection events =
        JfrLoaderToolkit.loadEvents(target.toFile())
            .apply(ItemFilters.type(SamplerWriter.CONTEXT_EVENT_NAME));
    assertTrue(events.hasItems());

    IAttribute<String> traceIdAttr = Attribute.attr("traceId", "traceId", UnitLookup.PLAIN_TEXT);

    events.forEach(
        iitem -> {
          IMemberAccessor<String, IItem> traceidAcessor = traceIdAttr.getAccessor(iitem.getType());
          iitem.forEach(
              item -> {
                assertEquals(traceid, traceidAcessor.getMember(item));
              });
        });
  }
}
