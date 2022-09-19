package com.datadog.profiling.leakmonitor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;
import javax.management.Notification;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.AdditionalMatchers;
import org.mockito.Mockito;

public class GcMonitorTest {

  public static Stream<Arguments> params() {
    return Stream.of(
        Arguments.of(mockNotification("MarkSweepCompact", "Tenured Gen"), "Tenured Gen", 0.91),
        Arguments.of(mockNotification("MarkSweepCompact", "Tenured Gen"), "Tenured Gen", 0.1),
        Arguments.of(mockNotification("MarkSweepCompact", "Tenured Gen"), "Tenured Gen", -0.1));
  }

  @ParameterizedTest
  @MethodSource("params")
  public void testGCMonitor(Notification notification, String poolName, double score) {
    Analyzer analyzer = mock(Analyzer.class);
    when(analyzer.analyze(
            Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong()))
        .thenReturn(score);
    Action action = mock(Action.class);
    GcMonitor sut = new GcMonitor(analyzer, new Object[][] {{poolName}}, action);
    sut.handleNotification(notification, null);
    if (score > 0.9) {
      verify(action, Mockito.times(1)).apply();
    } else if (score < 0) {
      verify(action, Mockito.times(1)).revert();
    } else {
      verifyNoInteractions(action);
    }
  }

  private static Notification mockNotification(String mxBeanName, String poolName) {
    Notification notification = mock(Notification.class);
    when(notification.getTimeStamp()).thenReturn(42L);
    when(notification.getMessage()).thenReturn(mxBeanName);
    CompositeData userData = mock(CompositeData.class);
    when(notification.getUserData()).thenReturn(userData);
    CompositeData gcInfo = mock(CompositeData.class);
    when(userData.get("gcInfo")).thenReturn(gcInfo);
    TabularData memoryUsageAfterGc = mock(TabularData.class);
    when(gcInfo.get("memoryUsageAfterGc")).thenReturn(memoryUsageAfterGc);
    CompositeData poolData = mock(CompositeData.class);
    when(memoryUsageAfterGc.get(AdditionalMatchers.aryEq(new Object[] {poolName})))
        .thenReturn(poolData);
    CompositeData gcData = mock(CompositeData.class);
    when(poolData.get("value")).thenReturn(gcData);
    when(gcData.get("used")).thenReturn(42L);
    when(gcData.get("committed")).thenReturn(42L);
    when(gcData.get("max")).thenReturn(42L);
    return notification;
  }
}
