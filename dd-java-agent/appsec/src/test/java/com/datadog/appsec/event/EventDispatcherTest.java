package com.datadog.appsec.event;

import static com.datadog.appsec.event.data.KnownAddresses.HEADERS_NO_COOKIES;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_CLIENT_IP;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_METHOD;
import static com.datadog.appsec.event.data.KnownAddresses.REQUEST_URI_RAW;
import static datadog.appsec.api.blocking.BlockingContentType.AUTO;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.appsec.event.EventDispatcher.DataSubscriptionSet;
import com.datadog.appsec.event.EventProducerService.DataSubscriberInfo;
import com.datadog.appsec.event.OrderedCallback.Priority;
import com.datadog.appsec.event.data.CaseInsensitiveMap;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.MapDataBundle;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class EventDispatcherTest {

  private final EventDispatcher dispatcher = new EventDispatcher();
  private final AppSecRequestContext ctx = mock(AppSecRequestContext.class);

  @Test
  void notifiesAboutDataInOrderWithTheSameFlow() throws Exception {
    // given
    DataSubscriptionSet set = new DataSubscriptionSet();
    DataListener dataListener1 = dataListener(Priority.DEFAULT);
    DataListener dataListener2 = dataListener(Priority.HIGH);
    DataListener dataListener3 = dataListener(Priority.HIGHEST);

    set.addSubscription(singletonList(REQUEST_CLIENT_IP), dataListener1);
    set.addSubscription(singletonList(REQUEST_CLIENT_IP), dataListener2);
    set.addSubscription(singletonList(REQUEST_METHOD), dataListener3);
    dispatcher.subscribeDataAvailable(set);
    GatewayContext gwCtx = new GatewayContext(true);

    // when
    DataSubscriberInfo subscribers =
        dispatcher.getDataSubscribers(REQUEST_CLIENT_IP, REQUEST_METHOD);
    DataBundle db = MapDataBundle.of(REQUEST_CLIENT_IP, "::1", REQUEST_METHOD, "GET");
    dispatcher.publishDataEvent(subscribers, ctx, db, gwCtx);

    // then
    ArgumentCaptor<ChangeableFlow> savedFlow3 = ArgumentCaptor.forClass(ChangeableFlow.class);
    ArgumentCaptor<ChangeableFlow> savedFlow2 = ArgumentCaptor.forClass(ChangeableFlow.class);
    ArgumentCaptor<ChangeableFlow> savedFlow1 = ArgumentCaptor.forClass(ChangeableFlow.class);
    InOrder inOrder = inOrder(dataListener3, dataListener2, dataListener1);
    inOrder
        .verify(dataListener3)
        .onDataAvailable(
            savedFlow3.capture(),
            eq(ctx),
            argThat(bundle -> bundle.hasAddress(REQUEST_CLIENT_IP)),
            eq(gwCtx));

    // then
    inOrder
        .verify(dataListener2)
        .onDataAvailable(
            savedFlow2.capture(),
            eq(ctx),
            argThat(bundle -> bundle.hasAddress(REQUEST_CLIENT_IP)),
            eq(gwCtx));
    assertSame(savedFlow3.getValue(), savedFlow2.getValue());

    // then
    inOrder
        .verify(dataListener1)
        .onDataAvailable(savedFlow1.capture(), eq(ctx), any(DataBundle.class), eq(gwCtx));
    assertSame(savedFlow2.getValue(), savedFlow1.getValue());

    // each listener is notified exactly once (InOrder alone does not reject duplicates)
    verify(dataListener3).onDataAvailable(any(), any(), any(), any());
    verify(dataListener2).onDataAvailable(any(), any(), any(), any());
    verify(dataListener1).onDataAvailable(any(), any(), any(), any());
  }

  @Test
  void thereIsOnlyOneNotificationIfSeveralSubscribedAddressesMatch() throws Exception {
    Map<String, List<String>> headers = new HashMap<>();
    headers.put("a", singletonList("a"));
    headers.put("b", singletonList("b"));
    MapDataBundle db =
        MapDataBundle.of(
            REQUEST_CLIENT_IP, "::1", HEADERS_NO_COOKIES, new CaseInsensitiveMap<>(headers));

    // given
    DataListener listener = dataListener(Priority.DEFAULT);

    DataSubscriptionSet set = new DataSubscriptionSet();
    set.addSubscription(asList(REQUEST_CLIENT_IP, HEADERS_NO_COOKIES), listener);
    dispatcher.subscribeDataAvailable(set);
    GatewayContext gwCtx = new GatewayContext(true);

    // when
    DataSubscriberInfo subscribers =
        dispatcher.getDataSubscribers(REQUEST_CLIENT_IP, HEADERS_NO_COOKIES);
    dispatcher.publishDataEvent(subscribers, ctx, db, gwCtx);

    // then
    assertFalse(subscribers.isEmpty());
    verify(listener).onDataAvailable(any(ChangeableFlow.class), eq(ctx), eq(db), eq(gwCtx));
  }

  @Test
  void blockingInterruptsDataListenerCalls() throws Exception {
    // given
    DataListener dataListener1 = dataListener(Priority.DEFAULT);
    DataListener dataListener2 = dataListener(Priority.DEFAULT);
    GatewayContext gwCtx = new GatewayContext(true);
    doAnswer(
            invocation -> {
              ChangeableFlow flow = invocation.getArgument(0);
              flow.setAction(new RequestBlockingAction(404, AUTO));
              return null;
            })
        .when(dataListener1)
        .onDataAvailable(any(ChangeableFlow.class), eq(ctx), any(DataBundle.class), eq(gwCtx));

    DataSubscriptionSet set = new DataSubscriptionSet();
    set.addSubscription(singletonList(REQUEST_CLIENT_IP), dataListener1);
    set.addSubscription(singletonList(REQUEST_CLIENT_IP), dataListener2);
    dispatcher.subscribeDataAvailable(set);

    // when
    DataSubscriberInfo subscribers = dispatcher.getDataSubscribers(REQUEST_CLIENT_IP);
    DataBundle db = MapDataBundle.of(REQUEST_CLIENT_IP, "::1");
    ChangeableFlow resultFlow =
        (ChangeableFlow) dispatcher.publishDataEvent(subscribers, ctx, db, gwCtx);

    // then
    verify(dataListener1)
        .onDataAvailable(any(ChangeableFlow.class), eq(ctx), any(DataBundle.class), eq(gwCtx));
    verify(dataListener2, never())
        .onDataAvailable(
            any(ChangeableFlow.class), eq(ctx), any(DataBundle.class), any(GatewayContext.class));
    assertTrue(resultFlow.isBlocking());
    RequestBlockingAction action = (RequestBlockingAction) resultFlow.getAction();
    assertEquals(404, action.getStatusCode());
    assertEquals(AUTO, action.getBlockingContentType());
  }

  @Test
  void nonTransientDataPublishingSavesTheBundleInTheContext() throws Exception {
    MapDataBundle db = MapDataBundle.of(REQUEST_CLIENT_IP, "::1");

    // given
    DataListener listener = dataListener(Priority.DEFAULT);

    DataSubscriptionSet set = new DataSubscriptionSet();
    set.addSubscription(singletonList(REQUEST_CLIENT_IP), listener);
    dispatcher.subscribeDataAvailable(set);
    GatewayContext gwCtx = new GatewayContext(false);

    // when
    DataSubscriberInfo subscribers = dispatcher.getDataSubscribers(REQUEST_CLIENT_IP);
    dispatcher.publishDataEvent(subscribers, ctx, db, gwCtx);

    // then
    verify(listener).onDataAvailable(any(ChangeableFlow.class), eq(ctx), eq(db), eq(gwCtx));
    verify(ctx).addAll(db);
  }

  @Test
  void emptySubscriberInfoIfNoSubscribersForAddress() {
    // when
    DataSubscriberInfo subscribers = dispatcher.getDataSubscribers(REQUEST_URI_RAW);

    // then
    assertTrue(subscribers.isEmpty());
  }

  @Test
  void savesTheSubscribedToEventsAndAddresses() {
    // given
    DataSubscriptionSet addressSet = new DataSubscriptionSet();
    DataListener dataListener = dataListener(Priority.DEFAULT);

    // when
    addressSet.addSubscription(singletonList(REQUEST_CLIENT_IP), dataListener);

    // then
    assertDoesNotThrow(() -> dispatcher.subscribeDataAvailable(addressSet));
  }

  @Test
  void throwsExpiredSubscriberInfoIfItIsFromADifferentEventDispatcher() {
    // when
    EventDispatcher anotherDispatcher = new EventDispatcher();
    DataSubscriberInfo subInfo = anotherDispatcher.getDataSubscribers(REQUEST_CLIENT_IP);
    GatewayContext gwCtx = new GatewayContext(false);

    // then
    assertThrows(
        ExpiredSubscriberInfoException.class,
        () ->
            dispatcher.publishDataEvent(
                subInfo, mock(AppSecRequestContext.class), mock(DataBundle.class), gwCtx));
  }

  private static DataListener dataListener(Priority priority) {
    DataListener listener = mock(DataListener.class);
    when(listener.getPriority()).thenReturn(priority);
    return listener;
  }
}
