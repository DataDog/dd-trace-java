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
  private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);
  private static final char[] EMPTY_CHAR_ARRAY = new char[0];

  private final List<List<EventListener>> eventListeners; // index: eventType.serial

  private static final int PRIORITY_SHIFT = 14;
  private static final int INDEX_MASK = 0x3FFF;

  // indexes are the ids we successively attribute to listeners
  // we support up to 2^14 (~16k) listeners in total
  private final List<DataListener> dataListenersIdx = new ArrayList<>();
  // index: address.serial; values: ordered array of (listener idx | priority << 14)
  private final List<char[]> dataListenerSubs;

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
      dataListenerSubs.add(EMPTY_CHAR_ARRAY);
    }
  }

  @Override
  public void subscribeEvent(EventType event, EventListener listener) {
    List<EventListener> eventListeners = this.eventListeners.get(event.ordinal());
    eventListeners.add(listener);
    eventListeners.sort(OrderedCallback.CallbackPriorityComparator.INSTANCE);
  }

  @Override
  public void subscribeDataAvailable(
      Collection<Address<?>> anyOfTheseAddresses, DataListener dataListener) {
    dataListenersIdx.add(dataListener);

    for (Address<?> addr : anyOfTheseAddresses) {
      int serial = addr.getSerial();
      char[] oldArray = dataListenerSubs.get(serial);
      List<DataListener> newList = new ArrayList<>(oldArray.length + 1);
      for (int i = 0; i < oldArray.length; i++) {
        newList.add(dataListenersIdx.get(i));
      }
      newList.add(dataListener);
      newList.sort(OrderedCallback.CallbackPriorityComparator.INSTANCE);
      char[] newArray = new char[newList.size()];
      for (int i = 0; i < newArray.length; i++) {
        DataListener listener = newList.get(i);
        char indexAndPriority =
            (char)
                (dataListenersIdx.indexOf(listener)
                    | listener.getPriority().ordinal() << PRIORITY_SHIFT);
        newArray[i] = indexAndPriority;
      }

      dataListenerSubs.set(serial, newArray);
    }
  }

  @Override
  public void publishEvent(AppSecRequestContext ctx, EventType event) {
    List<EventListener> eventListeners = this.eventListeners.get(event.ordinal());
    for (EventListener listener : eventListeners) {
      try {
        listener.onEvent(ctx, event);
      } catch (RuntimeException rte) {
        log.warn("AppSec callback exception", rte);
      }
    }
  }

  @Override
  public DataSubscriberInfo getDataSubscribers(
      AppSecRequestContext ctx, Address<?>... newAddresses) {
    if (newAddresses.length == 1) {
      // fast path
      Address<?> addr = newAddresses[0];
      char[] idsAndPriorities = dataListenerSubs.get(addr.getSerial());
      char[] subsIds = new char[idsAndPriorities.length];
      for (int i = 0; i < idsAndPriorities.length; i++) {
        subsIds[i] = (char) (idsAndPriorities[i] & INDEX_MASK);
      }
      return new DataSubscriberInfoImpl(subsIds);
    } else {
      // calculate union of listeners
      int numDataListeners = dataListenersIdx.size();
      BitSet bitSet = new BitSet(0xFFFF);
      for (Address<?> addr : newAddresses) {
        char[] subs = dataListenerSubs.get(addr.getSerial());
        for (int sub : subs) {
          bitSet.set(sub);
        }
      }
      char[] subsIds = new char[bitSet.cardinality()];

      // Copy bits into the array
      for (int bit = bitSet.nextSetBit(0), i = 0; bit >= 0; bit = bitSet.nextSetBit(bit + 1)) {
        // operate on index i here
        subsIds[i++] = (char) (bit & INDEX_MASK);
      }

      return new DataSubscriberInfoImpl(subsIds);
    }
  }

  @Override
  public Flow publishDataEvent(
      DataSubscriberInfo subscribers,
      AppSecRequestContext ctx,
      DataBundle newData,
      boolean isTransient) {
    if (!isTransient) {
      ctx.addAll(newData);
    }
    ChangeableFlow flow = new ChangeableFlow();
    for (int idx : ((DataSubscriberInfoImpl) subscribers).listenerIndices) {
      try {
        dataListenersIdx.get(idx).onDataAvailable(flow, ctx, newData);
      } catch (RuntimeException rte) {
        log.warn("AppSec callback exception", rte);
      }
      if (flow.isBlocking()) {
        break;
      }
    }

    return flow;
  }

  private static class DataSubscriberInfoImpl implements DataSubscriberInfo {
    final char[] listenerIndices;

    private DataSubscriberInfoImpl(char[] listenerIndices) {
      this.listenerIndices = listenerIndices;
    }

    @Override
    public boolean isEmpty() {
      return listenerIndices.length == 0;
    }
  }
}
