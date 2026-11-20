import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from contract_lookup import Source, lookup

CONTRACT = "internal-api/src/main/java/datadog/contract"
FUNCTION = "internal-api/src/main/java/datadog/trace/api/function"
LIB = "lib/src/main/java/lib"

BASE_FILES = {
    f"{CONTRACT}/ImpliedFor.java": "package datadog.contract;\npublic @interface ImpliedFor {}\n",
    f"{CONTRACT}/StaticLifetime.java": (
        "package datadog.contract;\n"
        "@ImpliedFor(ClassValue.class)\n"
        "public @interface StaticLifetime {}\n"),
    f"{FUNCTION}/BackgroundOnly.java": (
        "package datadog.trace.api.function;\npublic @interface BackgroundOnly {}\n"),
    f"{LIB}/Cache.java": (
        "package lib;\n"
        "import datadog.trace.api.function.BackgroundOnly;\n"
        "@BackgroundOnly\n"
        "public final class Cache {\n"
        "  public byte[] getUtf8(String s) { return null; }\n"
        "}\n"),
    f"{LIB}/Names.java": "package lib;\nclass Names extends ClassValue<String> {}\n",
    f"{LIB}/MoreNames.java": "package lib;\nclass MoreNames extends Names {}\n",
    f"{LIB}/Holder.java": "package lib;\nclass Holder {\n}\n",
    f"lib/src/test/java/lib/HolderTest.java": "package lib;\nclass HolderTest {\n}\n",
}


class ContractLookupTest(unittest.TestCase):
    def setUp(self):
        self.repo = tempfile.mkdtemp()
        self.cwd = os.getcwd()
        os.chdir(self.repo)
        self.git("init", "-q")
        for path, text in BASE_FILES.items():
            self.write(path, text)
        self.commit()
        self.base = self.git("rev-parse", "HEAD").strip()

    def tearDown(self):
        os.chdir(self.cwd)
        shutil.rmtree(self.repo)

    def git(self, *args):
        return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout

    def write(self, path, text):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(text)

    def commit(self):
        self.git("add", "-A")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c")

    def add_to_holder(self, *lines):
        self.write(f"{LIB}/Holder.java", "package lib;\nclass Holder {\n" + "".join(
            f"  {line}\n" for line in lines) + "}\n")

    def hits(self, rev=None, paths=None):
        out = lookup(Source(rev), self.base, paths)
        return [line for line in out[out.index("== 3. Lines that use them") + 1:]]

    def test_lists_marked_types_and_implied_subtypes(self):
        out = lookup(Source(None), self.base)
        self.assertIn(f"type Cache @BackgroundOnly {LIB}/Cache.java:3", out)
        self.assertIn(f"implied ClassValue @ImpliedFor {CONTRACT}/StaticLifetime.java:2", out)
        self.assertIn("implied Names (subtype)", out)
        self.assertIn("implied MoreNames (subtype)", out)

    def test_a_field_of_an_implied_subtype_is_a_hit(self):
        self.add_to_holder("private final MoreNames names = new MoreNames();")
        self.assertEqual(self.hits(), [
            f"{LIB}/Holder.java:3: [MoreNames]   private final MoreNames names = new MoreNames();"])

    def test_uncommitted_edits_count_unless_a_commit_is_named(self):
        self.add_to_holder("private final Names names = null;")
        self.assertEqual(len(self.hits()), 1)
        self.assertEqual(self.hits(rev="HEAD"), [])
        self.commit()
        self.assertEqual(len(self.hits(rev="HEAD")), 1)

    def test_an_annotated_field_is_marked_and_later_uses_match(self):
        self.add_to_holder("@datadog.contract.StaticLifetime private Object cache;")
        self.commit()
        self.base = self.git("rev-parse", "HEAD").strip()
        out = lookup(Source(None), self.base)
        self.assertIn(f"field cache @StaticLifetime {LIB}/Holder.java:3", out)
        self.add_to_holder("@datadog.contract.StaticLifetime private static Object cache;")
        self.assertEqual(self.hits(), [
            f"{LIB}/Holder.java:3: [cache]   @datadog.contract.StaticLifetime private static Object cache;"])

    def test_a_call_through_a_parameter_of_a_marked_type_is_a_hit(self):
        self.write(f"{LIB}/User.java", "package lib;\nclass User {\n"
                   "  static void use(Cache cache) {\n"
                   "    cache.getUtf8(\"x\");\n"
                   "  }\n}\n")
        self.commit()
        self.base = self.git("rev-parse", "HEAD").strip()
        self.write(f"{LIB}/User.java", "package lib;\nclass User {\n"
                   "  static void use(Cache cache) {\n"
                   "    cache.getUtf8(\"x\");\n"
                   "    cache.getUtf8(\"y\");\n"
                   "  }\n}\n")
        self.assertEqual(self.hits(), [
            f"{LIB}/User.java:5: [cache]     cache.getUtf8(\"y\");"])

    def test_paths_mode_covers_every_line_of_the_files(self):
        self.assertEqual(self.hits(paths=[f"{LIB}/MoreNames.java"]), [
            f"{LIB}/MoreNames.java:2: [MoreNames] class MoreNames extends Names {{}}"])

    def test_tests_and_comments_are_skipped(self):
        self.write("lib/src/test/java/lib/HolderTest.java",
                   "package lib;\nclass HolderTest {\n  Names names;\n}\n")
        self.add_to_holder("// Names are cached")
        self.assertEqual(self.hits(), [])


if __name__ == "__main__":
    unittest.main()
