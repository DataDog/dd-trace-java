package com.example.jsonbench.model;

import com.example.jsonbench.model.Dtos.Address;
import com.example.jsonbench.model.Dtos.Customer;
import com.example.jsonbench.model.Dtos.Line;
import com.example.jsonbench.model.Dtos.LineQuote;
import com.example.jsonbench.model.Dtos.Order;
import com.example.jsonbench.model.Dtos.OrderResponse;
import com.example.jsonbench.model.Dtos.SmallDto;
import com.example.jsonbench.model.Dtos.Status;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Samples {
  private Samples() {}

  public static final int REQUEST_LINES = 8;

  public static final int RESPONSE_RECOMMENDATIONS = 12;

  public static SmallDto small(int i) {
    SmallDto s = new SmallDto();
    s.setId(100_000L + i);
    s.setName("Product " + i);
    s.setEmail("user" + i + "@example.com");
    s.setActive(i % 2 == 0);
    s.setScore(i * 1.25);
    s.setAge(20 + i % 50);
    s.setCreatedAt(1_700_000_000_000L + i * 1000L);
    s.setStatus(Status.values()[i % Status.values().length]);
    s.setTags(List.of("tag" + (i % 7), "sale"));
    s.setRank(i % 3 == 0 ? null : i);
    return s;
  }

  public static List<SmallDto> smallList(int n) {
    List<SmallDto> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      list.add(small(i));
    }
    return list;
  }

  public static Address address(int i) {
    Address a = new Address();
    a.setLine1(i + " Market Street");
    a.setLine2(i % 2 == 0 ? null : "Suite " + i);
    a.setCity("San Francisco");
    a.setRegion("CA");
    a.setPostalCode("941" + String.format("%02d", i % 100));
    a.setCountry("US");
    return a;
  }

  public static Customer customer(int i) {
    Customer c = new Customer();
    c.setId(5000 + i);
    c.setFirstName("Grace");
    c.setLastName("Hopper");
    c.setEmail("grace" + i + "@example.com");
    c.setPhone("+1-415-555-01" + String.format("%02d", i % 100));
    c.setVip(i % 4 == 0);
    c.setLoyaltyPoints(i * 17);
    c.setMemberSince(1_500_000_000_000L + i);
    c.setLocale("en-US");
    c.setSegments(List.of("retail", "newsletter", "mobile"));
    return c;
  }

  public static Line line(int i) {
    Line l = new Line();
    l.setSku("SKU-" + (10_000 + i));
    l.setTitle("Widget model " + i);
    l.setQuantity(1 + i % 4);
    l.setUnitPriceCents(999 + i * 100L);
    l.setDiscountCents(i % 3 == 0 ? 0 : 50);
    l.setWeightKg(0.25 * (i + 1));
    l.setBackordered(i % 5 == 4);
    return l;
  }

  public static Order order(int i, int lines) {
    Order o = new Order();
    o.setOrderId("ord-" + i);
    o.setCreatedAt(1_700_000_000_000L + i);
    o.setUpdatedAt(1_700_000_100_000L + i);
    o.setCurrency("USD");
    List<Line> ls = new ArrayList<>(lines);
    long subtotal = 0;
    for (int l = 0; l < lines; l++) {
      Line line = line(l);
      ls.add(line);
      subtotal += line.getQuantity() * line.getUnitPriceCents() - line.getDiscountCents();
    }
    o.setLines(ls);
    o.setItemCount(lines);
    o.setSubtotalCents(subtotal);
    o.setTaxCents(subtotal * 8 / 100);
    o.setShippingCents(500);
    o.setTotalCents(subtotal + o.getTaxCents() + o.getShippingCents());
    o.setStatus(Status.NEW);
    o.setGift(i % 2 == 1);
    o.setGiftMessage(o.isGift() ? "Happy birthday!" : null);
    o.setChannel("web");
    o.setCouponCode(null);
    o.setCustomer(customer(i));
    o.setShippingAddress(address(i));
    o.setBillingAddress(address(i + 1));
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("source", "campaign-" + (i % 10));
    attrs.put("device", "ios");
    o.setAttributes(attrs);
    o.setExchangeRate(new BigDecimal("1.0825"));
    return o;
  }

  public static Order requestOrder() {
    return order(42, REQUEST_LINES);
  }

  public static OrderResponse respond(Order order) {
    OrderResponse r = new OrderResponse();
    r.setRequestId("req-" + order.getOrderId());
    r.setAccepted(order.getTotalCents() > 0);
    r.setProcessedAt(order.getUpdatedAt() + 1);
    order.setStatus(Status.PAID);
    r.setOrder(order);
    List<LineQuote> quotes = new ArrayList<>(order.getLines().size());
    for (Line l : order.getLines()) {
      LineQuote q = new LineQuote();
      q.setSku(l.getSku());
      q.setLineTotalCents(l.getQuantity() * l.getUnitPriceCents() - l.getDiscountCents());
      q.setTaxCents(q.getLineTotalCents() * 8 / 100);
      q.setEtaDays(l.isBackordered() ? 14 : 2);
      q.setInStock(!l.isBackordered());
      quotes.add(q);
    }
    r.setQuotes(quotes);
    r.setRecommendations(smallList(RESPONSE_RECOMMENDATIONS));
    return r;
  }
}
