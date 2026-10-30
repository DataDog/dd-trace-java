package com.example.jsonbench.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class Dtos {
  private Dtos() {}

  public enum Status {
    NEW,
    PAID,
    SHIPPED,
    CANCELLED
  }

  public static class SmallDto {
    private long id;
    private String name;
    private String email;
    private boolean active;
    private double score;
    private int age;
    private long createdAt;
    private Status status;
    private List<String> tags;
    private Integer rank;

    public long getId() {
      return id;
    }

    public void setId(long id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getEmail() {
      return email;
    }

    public void setEmail(String email) {
      this.email = email;
    }

    public boolean isActive() {
      return active;
    }

    public void setActive(boolean active) {
      this.active = active;
    }

    public double getScore() {
      return score;
    }

    public void setScore(double score) {
      this.score = score;
    }

    public int getAge() {
      return age;
    }

    public void setAge(int age) {
      this.age = age;
    }

    public long getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(long createdAt) {
      this.createdAt = createdAt;
    }

    public Status getStatus() {
      return status;
    }

    public void setStatus(Status status) {
      this.status = status;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setTags(List<String> tags) {
      this.tags = tags;
    }

    public Integer getRank() {
      return rank;
    }

    public void setRank(Integer rank) {
      this.rank = rank;
    }
  }

  public static class Address {
    private String line1;
    private String line2;
    private String city;
    private String region;
    private String postalCode;
    private String country;

    public String getLine1() {
      return line1;
    }

    public void setLine1(String line1) {
      this.line1 = line1;
    }

    public String getLine2() {
      return line2;
    }

    public void setLine2(String line2) {
      this.line2 = line2;
    }

    public String getCity() {
      return city;
    }

    public void setCity(String city) {
      this.city = city;
    }

    public String getRegion() {
      return region;
    }

    public void setRegion(String region) {
      this.region = region;
    }

    public String getPostalCode() {
      return postalCode;
    }

    public void setPostalCode(String postalCode) {
      this.postalCode = postalCode;
    }

    public String getCountry() {
      return country;
    }

    public void setCountry(String country) {
      this.country = country;
    }
  }

  public static class Customer {
    private long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private boolean vip;
    private int loyaltyPoints;
    private long memberSince;
    private String locale;
    private List<String> segments;

    public long getId() {
      return id;
    }

    public void setId(long id) {
      this.id = id;
    }

    public String getFirstName() {
      return firstName;
    }

    public void setFirstName(String firstName) {
      this.firstName = firstName;
    }

    public String getLastName() {
      return lastName;
    }

    public void setLastName(String lastName) {
      this.lastName = lastName;
    }

    public String getEmail() {
      return email;
    }

    public void setEmail(String email) {
      this.email = email;
    }

    public String getPhone() {
      return phone;
    }

    public void setPhone(String phone) {
      this.phone = phone;
    }

    public boolean isVip() {
      return vip;
    }

    public void setVip(boolean vip) {
      this.vip = vip;
    }

    public int getLoyaltyPoints() {
      return loyaltyPoints;
    }

    public void setLoyaltyPoints(int loyaltyPoints) {
      this.loyaltyPoints = loyaltyPoints;
    }

    public long getMemberSince() {
      return memberSince;
    }

    public void setMemberSince(long memberSince) {
      this.memberSince = memberSince;
    }

    public String getLocale() {
      return locale;
    }

    public void setLocale(String locale) {
      this.locale = locale;
    }

    public List<String> getSegments() {
      return segments;
    }

    public void setSegments(List<String> segments) {
      this.segments = segments;
    }
  }

  public static class Line {
    private String sku;
    private String title;
    private int quantity;
    private long unitPriceCents;
    private long discountCents;
    private double weightKg;
    private boolean backordered;

    public String getSku() {
      return sku;
    }

    public void setSku(String sku) {
      this.sku = sku;
    }

    public String getTitle() {
      return title;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public int getQuantity() {
      return quantity;
    }

    public void setQuantity(int quantity) {
      this.quantity = quantity;
    }

    public long getUnitPriceCents() {
      return unitPriceCents;
    }

    public void setUnitPriceCents(long unitPriceCents) {
      this.unitPriceCents = unitPriceCents;
    }

    public long getDiscountCents() {
      return discountCents;
    }

    public void setDiscountCents(long discountCents) {
      this.discountCents = discountCents;
    }

    public double getWeightKg() {
      return weightKg;
    }

    public void setWeightKg(double weightKg) {
      this.weightKg = weightKg;
    }

    public boolean isBackordered() {
      return backordered;
    }

    public void setBackordered(boolean backordered) {
      this.backordered = backordered;
    }
  }

  public static class Order {
    private String orderId;
    private long createdAt;
    private long updatedAt;
    private String currency;
    private long subtotalCents;
    private long taxCents;
    private long shippingCents;
    private long totalCents;
    private Status status;
    private boolean gift;
    private String giftMessage;
    private String channel;
    private String couponCode;
    private int itemCount;
    private Customer customer;
    private Address shippingAddress;
    private Address billingAddress;
    private List<Line> lines;
    private Map<String, String> attributes;
    private BigDecimal exchangeRate;

    public String getOrderId() {
      return orderId;
    }

    public void setOrderId(String orderId) {
      this.orderId = orderId;
    }

    public long getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(long createdAt) {
      this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
      return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
      this.updatedAt = updatedAt;
    }

    public String getCurrency() {
      return currency;
    }

    public void setCurrency(String currency) {
      this.currency = currency;
    }

    public long getSubtotalCents() {
      return subtotalCents;
    }

    public void setSubtotalCents(long subtotalCents) {
      this.subtotalCents = subtotalCents;
    }

    public long getTaxCents() {
      return taxCents;
    }

    public void setTaxCents(long taxCents) {
      this.taxCents = taxCents;
    }

    public long getShippingCents() {
      return shippingCents;
    }

    public void setShippingCents(long shippingCents) {
      this.shippingCents = shippingCents;
    }

    public long getTotalCents() {
      return totalCents;
    }

    public void setTotalCents(long totalCents) {
      this.totalCents = totalCents;
    }

    public Status getStatus() {
      return status;
    }

    public void setStatus(Status status) {
      this.status = status;
    }

    public boolean isGift() {
      return gift;
    }

    public void setGift(boolean gift) {
      this.gift = gift;
    }

    public String getGiftMessage() {
      return giftMessage;
    }

    public void setGiftMessage(String giftMessage) {
      this.giftMessage = giftMessage;
    }

    public String getChannel() {
      return channel;
    }

    public void setChannel(String channel) {
      this.channel = channel;
    }

    public String getCouponCode() {
      return couponCode;
    }

    public void setCouponCode(String couponCode) {
      this.couponCode = couponCode;
    }

    public int getItemCount() {
      return itemCount;
    }

    public void setItemCount(int itemCount) {
      this.itemCount = itemCount;
    }

    public Customer getCustomer() {
      return customer;
    }

    public void setCustomer(Customer customer) {
      this.customer = customer;
    }

    public Address getShippingAddress() {
      return shippingAddress;
    }

    public void setShippingAddress(Address shippingAddress) {
      this.shippingAddress = shippingAddress;
    }

    public Address getBillingAddress() {
      return billingAddress;
    }

    public void setBillingAddress(Address billingAddress) {
      this.billingAddress = billingAddress;
    }

    public List<Line> getLines() {
      return lines;
    }

    public void setLines(List<Line> lines) {
      this.lines = lines;
    }

    public Map<String, String> getAttributes() {
      return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
      this.attributes = attributes;
    }

    public BigDecimal getExchangeRate() {
      return exchangeRate;
    }

    public void setExchangeRate(BigDecimal exchangeRate) {
      this.exchangeRate = exchangeRate;
    }
  }

  public static class OrderResponse {
    private String requestId;
    private boolean accepted;
    private long processedAt;
    private Order order;
    private List<LineQuote> quotes;
    private List<SmallDto> recommendations;

    public String getRequestId() {
      return requestId;
    }

    public void setRequestId(String requestId) {
      this.requestId = requestId;
    }

    public boolean isAccepted() {
      return accepted;
    }

    public void setAccepted(boolean accepted) {
      this.accepted = accepted;
    }

    public long getProcessedAt() {
      return processedAt;
    }

    public void setProcessedAt(long processedAt) {
      this.processedAt = processedAt;
    }

    public Order getOrder() {
      return order;
    }

    public void setOrder(Order order) {
      this.order = order;
    }

    public List<LineQuote> getQuotes() {
      return quotes;
    }

    public void setQuotes(List<LineQuote> quotes) {
      this.quotes = quotes;
    }

    public List<SmallDto> getRecommendations() {
      return recommendations;
    }

    public void setRecommendations(List<SmallDto> recommendations) {
      this.recommendations = recommendations;
    }
  }

  public static class LineQuote {
    private String sku;
    private long lineTotalCents;
    private long taxCents;
    private int etaDays;
    private boolean inStock;

    public String getSku() {
      return sku;
    }

    public void setSku(String sku) {
      this.sku = sku;
    }

    public long getLineTotalCents() {
      return lineTotalCents;
    }

    public void setLineTotalCents(long lineTotalCents) {
      this.lineTotalCents = lineTotalCents;
    }

    public long getTaxCents() {
      return taxCents;
    }

    public void setTaxCents(long taxCents) {
      this.taxCents = taxCents;
    }

    public int getEtaDays() {
      return etaDays;
    }

    public void setEtaDays(int etaDays) {
      this.etaDays = etaDays;
    }

    public boolean isInStock() {
      return inStock;
    }

    public void setInStock(boolean inStock) {
      this.inStock = inStock;
    }
  }
}
