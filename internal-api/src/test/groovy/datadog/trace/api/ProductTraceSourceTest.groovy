package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class ProductTraceSourceTest extends DDSpecification {

  void 'test updateProduct'(){
    when:
    final result = ProductTraceSource.updateProduct(init, newProduct)

    then:
    result == expected

    where:
    init                   | newProduct             | expected
    ProductTraceSource.UNSET                      | ProductTraceSource.ASM | ProductTraceSource.ASM
    ProductTraceSource.ASM | ProductTraceSource.DSM | 6
  }

  void 'test isProductMarked'(){
    when:
    final result = ProductTraceSource.isProductMarked(value, product)

    then:
    result == expected

    where:
    value         | product       | expected
    ProductTraceSource.ASM | ProductTraceSource.ASM | true
    ProductTraceSource.DSM | ProductTraceSource.ASM | false
  }

  void 'test getBitfieldHex'(){
    when:
    final result = ProductTraceSource.getBitfieldHex(value)

    then:
    result == expected

    where:
    value         | expected
    ProductTraceSource.UNSET               | "00"
    ProductTraceSource.ASM | "02"
  }

  void 'test parseBitfieldHex'(){
    when:
    final result = ProductTraceSource.parseBitfieldHex(hex)

    then:
    result == expected

    where:
    hex  | expected
    "00" | ProductTraceSource.UNSET
    null | ProductTraceSource.UNSET
    ""   | ProductTraceSource.UNSET
    "02" | ProductTraceSource.ASM
  }
}
