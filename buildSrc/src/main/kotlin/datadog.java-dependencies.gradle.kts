plugins {
  java
  groovy
}

dependencies {
  testImplementation(getBundleFromVersionCatalog("spock"))
  testImplementation(getLibraryFromVersionCatalog("groovy"))
  testImplementation(getBundleFromVersionCatalog("test-logging"))
}
