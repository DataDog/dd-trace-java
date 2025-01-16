package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class ProductTsTest extends DDSpecification {

  void 'test updateProduct'(){
    when:
    final result = ProductTs.updateProduct(init, newProduct)

    then:
    result == expected

    where:
    init          | newProduct    | expected
    0             | ProductTs.ASM | ProductTs.ASM
    ProductTs.ASM | ProductTs.DSM | 6
  }

  void 'test isProductMarked'(){
    when:
    final result = ProductTs.isProductMarked(value, product)

    then:
    result == expected

    where:
    value         | product       | expected
    ProductTs.ASM | ProductTs.ASM | true
    ProductTs.DSM | ProductTs.ASM | false
  }

  void 'test getBitfieldHex'(){
    when:
    final result = ProductTs.getBitfieldHex(value)

    then:
    result == expected

    where:
    value         | expected
    0             | "00"
    ProductTs.ASM | "02"
  }

  void 'test parseBitfieldHex'(){
    when:
    final result = ProductTs.parseBitfieldHex(hex)

    then:
    result == expected

    where:
    hex  | expected
    "00" | 0
    null | 0
    ""   | 0
    "02" | ProductTs.ASM
  }
}
