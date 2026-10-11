class QuickReplyPlugin:
    def activate(self, host):
        self.host = host

    def deactivate(self):
        self.host = None

    def execute_chat_action(self, action_id, request):
        if action_id != "quick-reply":
            raise ValueError(f"Unknown action: {action_id}")
        context = self.host.read_dialogue_context(
            run_id=request["run_id"],
            session_id=request["session_id"],
            seed_text=request.get("seed_text", ""),
            direction=request.get("direction", ""),
        )
        suggestion = self.host.invoke_model("dialogue_suggestion", context)
        return {"suggestion": suggestion}


def create_plugin():
    return QuickReplyPlugin()
