import re
import unittest
from pathlib import Path

from apk import FetchError, missing_names, with_retries

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "extensions/jimaku/src/main/java"
NAMES = Path(__file__).resolve().parent / "youtube-names.txt"


class RetryTest(unittest.TestCase):
    def setUp(self):
        self.calls = 0
        self.waits = []

    def failing(self, times, status=504):
        def call():
            self.calls += 1
            if self.calls <= times:
                raise FetchError("https://example.org", status)
            return "ok"
        return call

    def test_retries_then_succeeds(self):
        result = with_retries(self.failing(2), attempts=3, wait=20, sleep=self.waits.append)
        self.assertEqual(result, "ok")
        self.assertEqual(self.calls, 3)
        self.assertEqual(self.waits, [20, 20])

    def test_retries_give_up(self):
        with self.assertRaises(FetchError):
            with_retries(self.failing(99), attempts=3, wait=20, sleep=self.waits.append)
        self.assertEqual(self.calls, 3)

    def test_curl_failure_is_retried(self):
        result = with_retries(self.failing(1, status=None), attempts=3, wait=20, sleep=self.waits.append)
        self.assertEqual(result, "ok")
        self.assertEqual(self.calls, 2)

    def test_client_error_is_not_retried(self):
        with self.assertRaises(FetchError):
            with_retries(self.failing(99, status=404), attempts=3, wait=20, sleep=self.waits.append)
        self.assertEqual(self.calls, 1)


class NamesTest(unittest.TestCase):
    def test_missing_names(self):
        dump = (
            "      resource 0x7f0b0123 id/player_subtitle_button\n"
            "      resource 0x7f0b0124 id/player_subtitle_button_x\n"
            "      resource 0x7f150001 string/bottom_sheet_accessibility_pane_title\n"
        )
        names = ["id/player_subtitle_button", "id/fullscreen_button", "string/bottom_sheet_accessibility_pane_title"]
        self.assertEqual(missing_names(dump, names), ["id/fullscreen_button"])

    def test_names_list_covers_the_code(self):
        message = "A YouTube lookup by name changed: update .github/follow-morphe/youtube-names.txt"
        sources = {path: path.read_text(encoding="utf-8") for path in JAVA.rglob("*.java")}
        lookups = {path.relative_to(JAVA).as_posix(): text.count("getIdentifier(") for path, text in sources.items()}
        self.assertEqual(
            {path: count for path, count in lookups.items() if count},
            {"app/jimaku/extension/Player.java": 1, "app/jimaku/extension/ui/Sheet.java": 1},
            message,
        )

        player = (JAVA / "app/jimaku/extension/Player.java").read_text(encoding="utf-8")
        found = set()
        for argument in re.findall(r"resourceId\(([^)]*)\)", player):
            if argument == "String name":
                continue
            literal = re.fullmatch(r'"([^"]+)"', argument)
            constant = re.fullmatch(r"(\w+)(?:\[[^\]]*\])?", argument)
            self.assertTrue(literal or constant, f"{message} (resourceId({argument}))")
            if literal:
                found.add("id/" + literal.group(1))
                continue
            declaration = re.search(r"\bString(?:\[\])? " + constant.group(1) + r" = ([^;]+);", player)
            self.assertIsNotNone(declaration, f"{message} ({constant.group(1)})")
            found.update("id/" + name for name in re.findall(r'"([^"]+)"', declaration.group(1)))

        sheet = (JAVA / "app/jimaku/extension/ui/Sheet.java").read_text(encoding="utf-8")
        for argument in re.findall(r"hostString\(([^)]*)\)", sheet):
            if argument == "String name":
                continue
            literal = re.fullmatch(r'"([^"]+)"', argument)
            self.assertTrue(literal, f"{message} (hostString({argument}))")
            found.add("string/" + literal.group(1))

        listed = {line.strip() for line in NAMES.read_text(encoding="utf-8").splitlines() if line.strip()}
        self.assertEqual(found, listed, message)


if __name__ == "__main__":
    unittest.main()
