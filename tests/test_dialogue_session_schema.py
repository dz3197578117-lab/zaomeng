from __future__ import annotations

import unittest

from src.web.api.schemas import CreateDialogueSessionRequest


class DialogueSessionSchemaTests(unittest.TestCase):
    def test_legacy_client_can_omit_observe_mode(self) -> None:
        payload = CreateDialogueSessionRequest(participants=["甲", "乙"])

        self.assertEqual(payload.mode, "observe")


if __name__ == "__main__":
    unittest.main()
