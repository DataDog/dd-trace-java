package com.datadog.appsec.event

import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import datadog.trace.api.gateway.Flow
import spock.lang.Specification

class EventDispatcherSpecification extends Specification {
  EventDispatcher dispatcher = new EventDispatcher()
  AppSecRequestContext ctx = Mock()

  void 'notifies about events in order'() {
    given:
    EventListener eventListener1 = Mock()
    EventListener eventListener2 = Mock()
    eventListener1.priority >> OrderedCallback.Priority.DEFAULT
    eventListener2.priority >> OrderedCallback.Priority.HIGH

    dispatcher.subscribeEvent(EventType.REQUEST_END, eventListener1)
    dispatcher.subscribeEvent(EventType.REQUEST_END, eventListener2)

    when:
    dispatcher.publishEvent(ctx, EventType.REQUEST_END)

    then:
    1 * eventListener2.onEvent(ctx, EventType.REQUEST_END)

    then:
    1 * eventListener1.onEvent(ctx, EventType.REQUEST_END)
  }

  void 'notifies about data in order with the same flow'() {
    Flow savedFlow1, savedFlow2, savedFlow3

    given:
    DataListener dataListener1 = Mock()
    DataListener dataListener2 = Mock()
    DataListener dataListener3 = Mock()
    dataListener1.priority >> OrderedCallback.Priority.DEFAULT
    dataListener2.priority >> OrderedCallback.Priority.HIGH
    dataListener3.priority >> OrderedCallback.Priority.HIGHEST

    dispatcher.subscribeDataAvailable([KnownAddresses.REQUEST_CLIENT_IP], dataListener1)
    dispatcher.subscribeDataAvailable([KnownAddresses.REQUEST_CLIENT_IP], dataListener2)
    dispatcher.subscribeDataAvailable([KnownAddresses.REQUEST_METHOD], dataListener3)

    when:
    def subscribers = dispatcher.getDataSubscribers(ctx,
      KnownAddresses.REQUEST_CLIENT_IP, KnownAddresses.REQUEST_METHOD)
    DataBundle db = MapDataBundle.of(
      KnownAddresses.REQUEST_CLIENT_IP, '::1',
      KnownAddresses.REQUEST_METHOD, 'GET')
    dispatcher.publishDataEvent(subscribers, ctx, db, true)

    then:
    1 * dataListener3.onDataAvailable(
      _ as Flow, ctx,
      { it.hasAddress(KnownAddresses.REQUEST_CLIENT_IP) }) >> { savedFlow3 = it[0] }

    then:
    1 * dataListener2.onDataAvailable(
      _ as Flow, ctx,
      { it.hasAddress(KnownAddresses.REQUEST_CLIENT_IP) }) >> { savedFlow2 = it[0] }
    savedFlow2.is(savedFlow3)

    then:
    1 * dataListener1.onDataAvailable(_ as Flow, ctx,
      _ as DataBundle) >> { savedFlow1 = it[0] }
    savedFlow1.is(savedFlow2)
  }

  void 'there is only one notification if several subscribed addresses match'() {
    MapDataBundle db = MapDataBundle.of(
      KnownAddresses.REQUEST_CLIENT_IP, '::1',
      KnownAddresses.HEADERS_NO_COOKIES, new CaseInsensitiveMap<>([a: ['a'], b: ['b']]))

    given:
    DataListener listener = Mock()
    listener.priority >> OrderedCallback.Priority.DEFAULT

    dispatcher.subscribeDataAvailable(
      [KnownAddresses.REQUEST_CLIENT_IP, KnownAddresses.HEADERS_NO_COOKIES],
      listener)

    when:
    def subscribers = dispatcher.getDataSubscribers(ctx,
      KnownAddresses.REQUEST_CLIENT_IP, KnownAddresses.HEADERS_NO_COOKIES)
    dispatcher.publishDataEvent(subscribers, ctx, db, true)

    then:
    assert !subscribers.empty
    1 * listener.onDataAvailable(_ as Flow, ctx, db)
  }

  void 'blocking interrupts data listener calls'() {
    def exception = new RuntimeException()

    given:
    DataListener dataListener1 = Mock()
    DataListener dataListener2 = Mock()
    [dataListener1, dataListener2].each {
      it.priority >> OrderedCallback.Priority.DEFAULT
    }

    dispatcher.subscribeDataAvailable([KnownAddresses.REQUEST_CLIENT_IP], dataListener1)
    dispatcher.subscribeDataAvailable([KnownAddresses.REQUEST_CLIENT_IP], dataListener2)

    when:
    def subscribers = dispatcher.getDataSubscribers(ctx, KnownAddresses.REQUEST_CLIENT_IP)
    DataBundle db = MapDataBundle.of(KnownAddresses.REQUEST_CLIENT_IP, '::1')
    ChangeableFlow resultFlow = dispatcher.publishDataEvent(subscribers, ctx, db, true)

    then:
    1 * dataListener1.onDataAvailable(_ as Flow, ctx, _ as DataBundle) >> {
      ChangeableFlow flow = it.first()
      flow.action = new Flow.Action.Throw(exception)
    }
    0 * dataListener2.onDataAvailable(_ as Flow, ctx, _ as DataBundle)
    assert resultFlow.blocking
    assert resultFlow.action.blockingException.is(exception)
  }

  void 'non transient data publishing saves the bundle in the context'() {
    MapDataBundle db = MapDataBundle.of(KnownAddresses.REQUEST_CLIENT_IP, '::1')

    given:
    DataListener listener = Mock()
    listener.priority >> OrderedCallback.Priority.DEFAULT

    dispatcher.subscribeDataAvailable([KnownAddresses.REQUEST_CLIENT_IP], listener)

    when:
    def subscribers = dispatcher.getDataSubscribers(ctx, KnownAddresses.REQUEST_CLIENT_IP)
    dispatcher.publishDataEvent(subscribers, ctx, db, false)

    then:
    1 * listener.onDataAvailable(_ as Flow, ctx, db)
    1 * ctx.addAll(db)
  }

  void 'empty subscriber info if no subscribers for address'() {
    when:
    def subscribers = dispatcher.getDataSubscribers(ctx, KnownAddresses.REQUEST_URI_RAW)

    then:
    subscribers.empty == true
  }
}
