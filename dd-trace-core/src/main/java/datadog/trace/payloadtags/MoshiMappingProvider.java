package datadog.trace.payloadtags;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.TypeRef;
import com.jayway.jsonpath.spi.mapper.MappingProvider;

public class MoshiMappingProvider implements MappingProvider {
  @Override
  public <T> T map(Object o, Class<T> aClass, Configuration configuration) {
    return null;
  }

  @Override
  public <T> T map(Object o, TypeRef<T> typeRef, Configuration configuration) {
    return null;
  }
}
