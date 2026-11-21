from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_lockfile_updates as check

CATALOG = """[versions]
junit5 = "5.14.1"
mockito = "4.4.0"
develocity = "4.6.1"

[libraries]
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit5" }
mokito-core = { module = "org.mockito:mockito-core", version.ref = "mockito" }
develocity = { module = "com.gradle:develocity-gradle-plugin", version.ref = "develocity" }

[bundles]
mockito = ["mokito-core"]  # trailing comment
junit5 = [
  "junit-jupiter",
]

[plugins]
shadow = { id = "com.gradleup.shadow", version = "9.4.2" }
"""
MODULE = """dependencies {
  implementation project(':internal-api')
  testImplementation libs.bundles.mockito
}
"""


class CheckLockfileUpdatesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.previous_cwd = Path.cwd()
        self.addCleanup(lambda: __import__("os").chdir(self.previous_cwd))
        __import__("os").chdir(self.root)
        self.run_git("init", "-q")
        self.write("gradle/libs.versions.toml", CATALOG)
        self.write("mod/build.gradle", MODULE)
        self.write("mod/gradle.lockfile", "org.mockito:mockito-core:4.4.0=testRuntimeClasspath\n")
        self.write("build-logic/tool/build.gradle.kts", "dependencies {\n  implementation(libs.develocity)\n}\n")
        self.commit("base")

    def run_git(self, *args):
        subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", *args], check=True, capture_output=True)

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def commit(self, message):
        self.run_git("add", "-A")
        self.run_git("commit", "-q", "-m", message)

    def problems(self):
        return check.find_problems("HEAD^1", "HEAD")

    def test_used_library_version_bump_without_lockfile_is_reported(self):
        self.write("gradle/libs.versions.toml", CATALOG.replace('mockito = "4.4.0"', 'mockito = "5.0.0"'))
        self.commit("bump")
        self.assertEqual(len(self.problems()), 1)
        self.assertIn("libs.bundles.mockito", self.problems()[0])

    def test_version_bump_with_lockfile_is_accepted(self):
        self.write("gradle/libs.versions.toml", CATALOG.replace('mockito = "4.4.0"', 'mockito = "5.0.0"'))
        self.write("mod/gradle.lockfile", "org.mockito:mockito-core:5.0.0=testRuntimeClasspath\n")
        self.commit("bump with lock")
        self.assertEqual(self.problems(), [])

    def test_catalog_entry_unused_by_locked_modules_is_accepted(self):
        self.write("gradle/libs.versions.toml", CATALOG.replace('junit5 = "5.14.1"', 'junit5 = "5.15.0"'))
        self.commit("bump unused")
        self.assertEqual(self.problems(), [])

    def test_build_logic_dependency_is_not_locked(self):
        self.write("gradle/libs.versions.toml", CATALOG.replace('develocity = "4.6.1"', 'develocity = "4.7.0"'))
        self.commit("bump build logic")
        self.assertEqual(self.problems(), [])

    def test_plugin_and_comment_only_changes_are_accepted(self):
        self.write("gradle/libs.versions.toml",
                   CATALOG.replace('"9.4.2"', '"9.5.0"').replace("# trailing comment", "# other comment"))
        self.commit("plugin and comment")
        self.assertEqual(self.problems(), [])

    def test_new_external_dependency_without_lockfile_is_reported(self):
        self.write("mod/build.gradle", MODULE.replace("}\n", "  api group: 'org.foo', name: 'bar', version: '1'\n}\n"))
        self.commit("add dep")
        self.assertEqual(len(self.problems()), 1)
        self.assertIn("mod/build.gradle", self.problems()[0])

    def test_removed_external_dependency_without_lockfile_is_reported(self):
        self.write("mod/build.gradle", "dependencies {\n  implementation project(':internal-api')\n}\n")
        self.commit("remove dep")
        self.assertEqual(len(self.problems()), 1)

    def test_project_dependency_only_is_accepted(self):
        self.write("mod/build.gradle", MODULE.replace("}\n", "  implementation project(':other')\n}\n"))
        self.commit("add project dep")
        self.assertEqual(self.problems(), [])

    def test_module_without_lockfile_is_accepted(self):
        self.write("new/build.gradle", "dependencies {\n  implementation 'org.foo:bar:1'\n}\n")
        self.commit("new module")
        self.assertEqual(self.problems(), [])

    def test_commented_declaration_is_accepted(self):
        self.write("mod/build.gradle", MODULE.replace("}\n", "  // implementation 'org.foo:bar:1'\n}\n"))
        self.commit("comment")
        self.assertEqual(self.problems(), [])

    def test_multi_line_bundle_and_version_ref_are_resolved(self):
        libraries, bundles = check.parse_catalog(CATALOG)
        self.assertIn('version = "5.14.1"', libraries["junit-jupiter"])
        self.assertEqual(bundles["junit5"], frozenset({"junit-jupiter"}))
        self.assertEqual(bundles["mockito"], frozenset({"mokito-core"}))


if __name__ == "__main__":
    unittest.main()
