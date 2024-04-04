package datadog.trace.core.datastreams

import datadog.trace.core.test.DDCoreSpecification

class DataSetHashBuilderTest extends DDCoreSpecification {

  def "Dataset hash generation"() {
    given:
    var tag = "ds.namespace=s3://my_bucket"
    var builderOne = new DefaultPathwayContext.DataSetHashBuilder()
    builderOne.addValue(tag)

    var builderTwo = new DefaultPathwayContext.DataSetHashBuilder()
    builderTwo.addValue(tag)

    expect:
    // hashing should be consistent
    assert builderOne.addValue("0") == builderTwo.addValue("0")
    // different parent hashes should produce different results
    assert  builderOne.addValue("1") != builderTwo.addValue("0")
  }
}
