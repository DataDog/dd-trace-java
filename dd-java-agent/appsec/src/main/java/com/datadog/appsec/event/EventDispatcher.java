package com.datadog.appsec.event;

import static com.datadog.appsec.event.EventType.NUM_EVENT_TYPES;

import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.gateway.Flow;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventDispatcher implements EventProducerService {
  private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);
  private static final char[] EMPTY_CHAR_ARRAY = new char[0];

  private List<List<EventListener>> eventListeners; // index: eventType.serial

  private static final int PRIORITY_SHIFT = 14;
  private static final int INDEX_MASK = 0x3FFF;

  // indexes are the ids we successively attribute to listeners
  // we support up to 2^14 (~16k) listeners in total
  private List<DataListener> dataListenersIdx;
  // index: address.serial; values: ordered array of (listener idx | priority << 14)
  private List<char[]> dataListenerSubs;

  public EventDispatcher() {
    KnownAddresses.HEADERS_NO_COOKIES.getKey(); // force class initialization

    // empty subscriptions
    eventListeners = new ArrayList<>(NUM_EVENT_TYPES);
    for (int i = 0; i < NUM_EVENT_TYPES; i++) {
      eventListeners.add(Collections.emptyList());
    }

    final int addressCount = Address.instanceCount();
    dataListenerSubs = new ArrayList<>(addressCount);
    for (int i = 0; i < addressCount; i++) {
      dataListenerSubs.add(EMPTY_CHAR_ARRAY);
    }
  }

  public static class EventSubscriptionSet {
    private final List<List<EventListener>> eventListeners; // index: eventType.serial

    public EventSubscriptionSet() {
      KnownAddresses.HEADERS_NO_COOKIES.getKey(); // force class initialization

      eventListeners = new ArrayList<>(NUM_EVENT_TYPES);
      for (int i = 0; i < NUM_EVENT_TYPES; i++) {
        eventListeners.add(new ArrayList<>(2));
      }
    }

    public void addSubscription(EventType event, EventListener listener) {
      List<EventListener> eventListeners = this.eventListeners.get(event.ordinal());
      eventListeners.add(listener);
    }
  }

  public void subscribeEvents(EventSubscriptionSet subscriptionSet) {
    for (List<EventListener> eventListener : subscriptionSet.eventListeners) {
      eventListener.sort(OrderedCallback.CallbackPriorityComparator.INSTANCE);
    }
    this.eventListeners = subscriptionSet.eventListeners;
  }

  public static class DataSubscriptionSet {
    private final Map<DataListener, Integer> indexes = new HashMap<>();
    // index: addr.serial
    private final List<List<DataListener>> addrSubs;

    public DataSubscriptionSet() {
      final int addressCount = Address.instanceCount();
      addrSubs = new ArrayList<>(addressCount);
      for (int i = 0; i < addressCount; i++) {
        addrSubs.add(new ArrayList<>());
      }
    }

    public void addSubscription(
        Collection<Address<?>> anyOfTheseAddresses, DataListener dataListener) {
      indexes.put(dataListener, indexes.size());

      for (Address<?> addr : anyOfTheseAddresses) {
        int serial = addr.getSerial();
        List<DataListener> dataListeners = addrSubs.get(serial);
        dataListeners.add(dataListener);
      }
    }
  }

  public void subscribeDataAvailable(DataSubscriptionSet subSet) {
    // convert index from map listener -> int to
    // list of the listeners where the index is the int
    int numListeners = subSet.indexes.size();
    List<DataListener> newDataListenersIdx = nullFilledList(numListeners);
    for (Map.Entry<DataListener, Integer> e : subSet.indexes.entrySet()) {
      newDataListenersIdx.set(e.getValue(), e.getKey());
    }

    int addressCount = Address.instanceCount();
    ArrayList<char[]> newDataListenerSubs = new ArrayList<>(addressCount);

    for (int addrSerial = 0; addrSerial < addressCount; addrSerial++) {
      List<DataListener> listenersList = subSet.addrSubs.get(addrSerial);
      listenersList.sort(OrderedCallback.CallbackPriorityComparator.INSTANCE);

      // convert list of listeners to char array of their indexes + priority
      char[] newArray = new char[listenersList.size()];
      for (int i = 0; i < newArray.length; i++) {
        DataListener listener = listenersList.get(i);
        char indexAndPriority =
            (char)
                (subSet.indexes.get(listener) | listener.getPriority().ordinal() << PRIORITY_SHIFT);
        newArray[i] = indexAndPriority;
      }

      newDataListenerSubs.add(newArray);
    }

    dataListenersIdx = newDataListenersIdx;
    dataListenerSubs = newDataListenerSubs;
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

  private static <T> List<T> nullFilledList(int numElements) {
    ArrayList<T> ts = new ArrayList<>(numElements);
    for (int i = 0; i < numElements; i++) {
      ts.add(null);
    }
    return ts;
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
