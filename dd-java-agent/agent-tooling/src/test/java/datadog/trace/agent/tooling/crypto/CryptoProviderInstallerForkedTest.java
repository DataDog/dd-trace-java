package datadog.trace.agent.tooling.crypto;

import static datadog.trace.agent.tooling.crypto.CryptoProviderInstaller.Outcome.ALREADY_PRESENT;
import static datadog.trace.agent.tooling.crypto.CryptoProviderInstaller.Outcome.INSTALLED;
import static datadog.trace.agent.tooling.crypto.CryptoProviderInstaller.Outcome.NOT_ON_CLASSPATH;
import static datadog.trace.agent.tooling.crypto.CryptoProviderInstaller.Outcome.SKIPPED_CUSTOM_SECURITY_PROPERTIES;
import static datadog.trace.agent.tooling.crypto.CryptoProviderInstaller.Outcome.SKIPPED_FIPS;
import static datadog.trace.agent.tooling.crypto.CryptoProviderInstaller.Outcome.SKIPPED_NON_JDK_PROVIDER;
import static datadog.trace.agent.tooling.crypto.CryptoProviderInstaller.Outcome.UNHEALTHY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CryptoProviderInstallerForkedTest {
  private static final String FAKE = FakeNativeProvider.class.getName();

  @TempDir File tempDir;

  private File noFipsFlag;
  private List<String> providersBefore;

  @BeforeEach
  void setUp() {
    FakeNativeProvider.reset();
    noFipsFlag = new File(tempDir, "missing");
    providersBefore = providerNames();
  }

  @AfterEach
  void tearDown() {
    Security.removeProvider(FakeNativeProvider.NAME);
    Security.removeProvider(ConstructedProvider.NAME);
    Security.removeProvider("TestOther");
    Security.removeProvider("TestFIPS");
    System.clearProperty("java.security.properties");
    System.clearProperty("org.bouncycastle.fips.approved_only");
    assertEquals(providersBefore, providerNames());
  }

  @Test
  void installsHealthyProviderAtPositionOne() {
    assertEquals(INSTALLED, install(FAKE));
    assertSame(FakeNativeProvider.INSTANCE, Security.getProviders()[0]);
    assertEquals(providersBefore, providerNames().subList(1, providerNames().size()));
  }

  @Test
  void secondInstallIsNoOp() {
    assertEquals(INSTALLED, install(FAKE));
    int count = Security.getProviders().length;
    assertEquals(ALREADY_PRESENT, install(FAKE));
    assertEquals(count, Security.getProviders().length);
  }

  @Test
  void usesNoArgConstructorWithoutInstanceField() {
    assertEquals(INSTALLED, install(ConstructedProvider.class.getName()));
    assertEquals(ConstructedProvider.NAME, Security.getProviders()[0].getName());
  }

  @Test
  void missingClassLeavesProvidersUntouched() {
    assertEquals(NOT_ON_CLASSPATH, install("com.example.DoesNotExist"));
    assertEquals(providersBefore, providerNames());
  }

  @Test
  void skipsWhenNativeLibraryFailedToLoad() {
    FakeNativeProvider.loadingError = new UnsatisfiedLinkError("no native lib");
    assertEquals(UNHEALTHY, install(FAKE));
    assertNull(Security.getProvider(FakeNativeProvider.NAME));
  }

  @Test
  void skipsWhenSelfTestsFail() {
    FakeNativeProvider.healthy = false;
    assertEquals(UNHEALTHY, install(FAKE));
    assertNull(Security.getProvider(FakeNativeProvider.NAME));
  }

  @Test
  void skipsFipsBuildOfProvider() {
    FakeNativeProvider.fips = true;
    assertEquals(SKIPPED_FIPS, install(FAKE));
    assertNull(Security.getProvider(FakeNativeProvider.NAME));
  }

  @Test
  void skipsWhenKernelIsInFipsMode() throws IOException {
    File flag = new File(tempDir, "fips_enabled");
    try (FileOutputStream out = new FileOutputStream(flag)) {
      out.write("1\n".getBytes(StandardCharsets.US_ASCII));
    }
    assertEquals(SKIPPED_FIPS, CryptoProviderInstaller.install(FAKE, loader(), flag));
    assertNull(Security.getProvider(FakeNativeProvider.NAME));
  }

  @Test
  void ignoresKernelFlagWhenFipsIsOff() throws IOException {
    File flag = new File(tempDir, "fips_enabled");
    try (FileOutputStream out = new FileOutputStream(flag)) {
      out.write("0\n".getBytes(StandardCharsets.US_ASCII));
    }
    assertEquals(INSTALLED, CryptoProviderInstaller.install(FAKE, loader(), flag));
  }

  @Test
  void skipsWhenFipsProviderIsRegistered() {
    Security.addProvider(new NamedProvider("TestFIPS"));
    assertEquals(SKIPPED_FIPS, install(FAKE));
    assertNull(Security.getProvider(FakeNativeProvider.NAME));
  }

  @Test
  void skipsInBouncyCastleApprovedOnlyMode() {
    System.setProperty("org.bouncycastle.fips.approved_only", "true");
    assertEquals(SKIPPED_FIPS, install(FAKE));
  }

  @Test
  void skipsWithCustomSecurityPropertiesFile() {
    System.setProperty("java.security.properties", "/etc/custom.security");
    assertEquals(SKIPPED_CUSTOM_SECURITY_PROPERTIES, install(FAKE));
    assertNull(Security.getProvider(FakeNativeProvider.NAME));
  }

  @Test
  void skipsWhenAppAlreadyRegisteredItsOwnProvider() {
    Security.addProvider(new NamedProvider("TestOther"));
    assertEquals(SKIPPED_NON_JDK_PROVIDER, install(FAKE));
    assertNull(Security.getProvider(FakeNativeProvider.NAME));
  }

  @Test
  void skipsWhenAppRegistersProviderWhileProviderInitialises() {
    FakeNativeProvider.beforeHealthCheck =
        () -> Security.addProvider(new NamedProvider("TestOther"));
    assertEquals(SKIPPED_NON_JDK_PROVIDER, install(FAKE));
    assertNull(Security.getProvider(FakeNativeProvider.NAME));
  }

  @Test
  void jdkProvidersAreRecognised() {
    for (Provider provider : Security.getProviders()) {
      assertTrue(CryptoProviderInstaller.isJdkProvider(provider), provider.getName());
    }
    assertFalse(CryptoProviderInstaller.isJdkProvider(new NamedProvider("TestOther")));
  }

  @Test
  void defaultJdkIsNotFips() {
    assertFalse(CryptoProviderInstaller.isKernelFipsMode(noFipsFlag));
    for (Provider provider : Security.getProviders()) {
      assertFalse(CryptoProviderInstaller.isFipsProvider(provider), provider.getName());
    }
  }

  private CryptoProviderInstaller.Outcome install(String className) {
    return CryptoProviderInstaller.install(className, loader(), noFipsFlag);
  }

  private static ClassLoader loader() {
    return CryptoProviderInstallerForkedTest.class.getClassLoader();
  }

  private static List<String> providerNames() {
    return Arrays.stream(Security.getProviders())
        .map(Provider::getName)
        .collect(Collectors.toList());
  }

  static final class NamedProvider extends Provider {
    @SuppressWarnings("deprecation")
    NamedProvider(String name) {
      super(name, 1.0, name);
    }
  }
}
