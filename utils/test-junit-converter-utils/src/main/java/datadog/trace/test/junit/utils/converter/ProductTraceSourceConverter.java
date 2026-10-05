package datadog.trace.test.junit.utils.converter;

import static datadog.trace.api.ProductTraceSource.APM;
import static datadog.trace.api.ProductTraceSource.ASM;
import static datadog.trace.api.ProductTraceSource.DBM;
import static datadog.trace.api.ProductTraceSource.DSM;
import static datadog.trace.api.ProductTraceSource.UNSET;

import java.util.HashMap;
import java.util.Map;

public class ProductTraceSourceConverter extends AbstractClassConstantConvertor<Integer> {
  private static final Map<String, Integer> MAPPING;

  static {
    MAPPING = new HashMap<>();
    MAPPING.put("UNSET", UNSET);
    MAPPING.put("APM", APM);
    MAPPING.put("ASM", ASM);
    MAPPING.put("DSM", DSM);
    MAPPING.put("DBM", DBM);
  }

  @Override
  protected String className() {
    return "ProductTraceSource";
  }

  @Override
  protected Map<String, Integer> mapping() {
    return MAPPING;
  }
}
