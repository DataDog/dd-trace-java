package datadog.remoteconfig.state;

import datadog.remoteconfig.Product;
import datadog.remoteconfig.ReportableException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import datadog.trace.util.HashingUtils;

public class ParsedConfigKey implements ConfigKey {

  private static final Pattern EXTRACT_PRODUCT_REGEX =
      Pattern.compile("([^/]+)(/\\d+)?/([^/]+)/([^/]+)/[^/]+");

  private final String originalKey;
  private final String org;
  private final Integer version;
  private final String productName;
  private final String configId;
  private final Product product;

  ParsedConfigKey(
      String originalKey, String org, Integer version, String productName, String configId) {
    this.originalKey = originalKey;
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
      throw new ReportableException("Not a valid config key: " + configKey);
    }
    String org = matcher.group(1);
    String version = matcher.group(2);
    // version group can be null or "/" + number. so if it is not null, parse int without first char
    Integer parsedVersion = version != null ? Integer.parseInt(version.substring(1)) : null;
    String product = matcher.group(3);
    String configId = matcher.group(4);

    return new ParsedConfigKey(configKey, org, parsedVersion, product, configId);
  }

  @Override
  public Product getProduct() {
    return product;
  }

  @Override
  public String getProductName() {
    return productName;
  }

  @Override
  public String getOrg() {
    return org;
  }

  @Override
  public Integer getVersion() {
    return version;
  }

  @Override
  public String getConfigId() {
    return configId;
  }

  @Override
  public String toString() {
    return originalKey;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ParsedConfigKey that = (ParsedConfigKey) o;
    return Objects.equals(originalKey, that.originalKey);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(originalKey);
  }
}
