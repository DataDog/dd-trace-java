package datadog.remoteconfig.state;

import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParsedConfigKey {

  private static final Pattern EXTRACT_PRODUCT_REGEX =
      Pattern.compile("([^/]+)(/\\d+)?/([^/]+)/([^/]+)/[^/]+");

  private final String orginalKey;
  private final String org;
  private final Integer version;
  private final String productName;
  private final String configId;
  private final Product product;

  ParsedConfigKey(
      String orginalKey, String org, Integer version, String productName, String configId) {
    this.orginalKey = orginalKey;
    this.org = org;
    this.version = version;
    this.productName = productName;
    this.configId = configId;

    Product parsedProduct = Product._UNKNOWN;
    try {
      parsedProduct = Product.valueOf(productName.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException iae) {
    }
    this.product = parsedProduct;
  }

  public static ParsedConfigKey parse(String configKey) {
    Matcher matcher = EXTRACT_PRODUCT_REGEX.matcher(configKey);
    if (!matcher.matches()) {
      throw new ConfigurationPoller.ReportableException("Not a valid config key: " + configKey);
    }
    String org = matcher.group(1);
    String version = matcher.group(2);
    // version group can be null or "/" + number. so if it is not null, parse int without first char
    Integer parsedVersion = version != null ? Integer.parseInt(version.substring(1)) : null;
    String product = matcher.group(3);
    String configId = matcher.group(4);

    return new ParsedConfigKey(configKey, org, parsedVersion, product, configId);
  }

  public Product getProduct() {
    return product;
  }

  public String getProductName() {
    return productName;
  }

  public String getOrg() {
    return org;
  }

  public Integer getVersion() {
    return version;
  }

  public String getConfigId() {
    return configId;
  }

  @Override
  public String toString() {
    return orginalKey;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ParsedConfigKey that = (ParsedConfigKey) o;
    return Objects.equals(orginalKey, that.orginalKey);
  }

  @Override
  public int hashCode() {
    return Objects.hash(orginalKey);
  }
}
