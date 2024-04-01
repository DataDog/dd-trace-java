package datadog.trace.core.datastreams

import datadog.trace.core.test.DDCoreSpecification

class DataSetHashBuilderTest extends DDCoreSpecification {

  def "Dataset hash generation"() {
    given:
    var tag = "ds.namespace=s3://my_bucket"
    var builderOne = new DefaultPathwayContext.DataSetHashBuilder()
    builderOne.addTag(tag)

    var builderTwo = new DefaultPathwayContext.DataSetHashBuilder()
    builderTwo.addTag(tag)

    expect:
    // hashing should be consistent
    assert builderOne.generateDataSourceHash(0) == builderTwo.generateDataSourceHash(0)
    // different parent hashes should produce different results
    assert  builderOne.generateDataSourceHash(1) != builderTwo.generateDataSourceHash(0)
  }
}
