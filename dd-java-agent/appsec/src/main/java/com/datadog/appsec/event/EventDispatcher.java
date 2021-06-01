package com.datadog.appsec.event;

import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.gateway.Flow;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventDispatcher implements EventProducerService, EventConsumerService {
  private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);
  private static final int[] EMPTY_INT_ARRAY = new int[0];

  private final List<List<EventListener>> eventListeners; // index: eventType.serial

  private final List<DataListener> dataListenersIdx = new ArrayList<>();
  private final List<int[]> dataListenerSubs; // index: address.serial

  public EventDispatcher() {
    KnownAddresses.HEADERS_NO_COOKIES.getKey(); // force class initialization

    int eventCount = EventType.values().length;
    eventListeners = new ArrayList<>(eventCount);
    for (int i = 0; i < eventCount; i++) {
      eventListeners.add(new ArrayList<>(2));
    }

    final int addressCount = Address.instanceCount();
    dataListenerSubs = new ArrayList<>(addressCount);
    for (int i = 0; i < addressCount; i++) {
      dataListenerSubs.add(EMPTY_INT_ARRAY);
    }
  }

  @Override
  public void subscribeEvent(EventType event, EventListener listener) {
    List<EventListener> eventListeners = this.eventListeners.get(event.serial);
    eventListeners.add(listener);
    eventListeners.sort(OrderedCallback.OrderedCallbackComparator.INSTANCE);
  }

  @Override
  public void subscribeDataAvailable(
      Collection<Address<?>> anyOfTheseAddresses, DataListener dataListener) {
    dataListenersIdx.add(dataListener);
    int id = dataListenersIdx.size() - 1;

    for (Address<?> addr : anyOfTheseAddresses) {
      int serial = addr.getSerial();
      int[] oldArray = dataListenerSubs.get(serial);
      List<DataListener> newList = new ArrayList<>(oldArray.length + 1);
      for (int i = 0; i < oldArray.length; i++) {
        newList.add(dataListenersIdx.get(i));
      }
      newList.add(dataListener);
      newList.sort(OrderedCallback.OrderedCallbackComparator.INSTANCE);
      int[] newArray = new int[newList.size()];
      for (int i = 0; i < newArray.length; i++) {
        newArray[i] = dataListenersIdx.indexOf(newList.get(i));
      }

      dataListenerSubs.set(serial, newArray);
    }
  }

  @Override
  public Flow publishEvent(AppSecRequestContext ctx, EventType event) {
    List<EventListener> eventListeners = this.eventListeners.get(event.serial);
    ChangeableFlow flow = new ChangeableFlow();
    for (EventListener listener : eventListeners) {
      try {
        listener.onEvent(flow, ctx, event);
      } catch (RuntimeException rte) {
        LOG.warn("AppSec callback exception", rte);
      }
      if (flow.isBlocking()) {
        break;
      }
    }

    return flow;
  }

  @Override
  public DataSubscriberInfo getDataSubscribers(
      AppSecRequestContext ctx, Address<?>... newAddresses) {
    if (newAddresses.length == 1) {
      // fast path
      Address<?> addr = newAddresses[0];
      int[] subs = dataListenerSubs.get(addr.getSerial());
      return new DataSubscriberInfoImpl(subs);
    } else {
      int numDataListeners = dataListenersIdx.size();
      BitSet bitSet = new BitSet(numDataListeners);
      for (Address<?> addr : newAddresses) {
        int[] subs = dataListenerSubs.get(addr.getSerial());
        for (int sub : subs) {
          bitSet.set(sub);
        }
      }
      int[] subs = new int[bitSet.cardinality()];
      return new DataSubscriberInfoImpl(subs);
    }
  }

  @Override
  public Flow publishDataEvent(
      DataSubscriberInfo subscribers,
      AppSecRequestContext ctx,
      DataBundle newData,
      boolean transyent) {
    if (!transyent) {
      ctx.addAll(newData);
    }
    ChangeableFlow flow = new ChangeableFlow();
    for (int idx : ((DataSubscriberInfoImpl) subscribers).listenerIndices) {
      try {
        dataListenersIdx.get(idx).onDataAvailable(flow, ctx, newData);
      } catch (RuntimeException rte) {
        LOG.warn("AppSec callback exception", rte);
      }
      if (flow.isBlocking()) {
        break;
      }
    }

    return flow;
  }

  private static class DataSubscriberInfoImpl implements DataSubscriberInfo {
    final int[] listenerIndices;

    private DataSubscriberInfoImpl(int[] listenerIndices) {
      this.listenerIndices = listenerIndices;
    }

    @Override
    public boolean isEmpty() {
      return listenerIndices.length == 0;
    }
  }
}
