#!/usr/bin/env python3
"""Tiny stand-in for Gemini's generateContent endpoint, for emulator testing
without a real API key. Debug builds only:

    python3 tools/gemtype/mock_gemini.py     # listens on :8765
    adb reverse tcp:8765 tcp:8765
    make install ABI=x86_64 GRADLE_ARGS=-Pgemtype.apiBase=http://localhost:8765/v1beta/models

Each request body is printed so you can check what the app sends (the key
itself is never printed). Replies use the same structured-JSON shape the app
asks Gemini for: {"rewritten": "..."}. Failure cases for the Test key button:
key "bad-key" -> 400 invalid key; any model id not starting "gemini-3" -> 404.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

# Canned rewrites keyed by the first words of each action's prompt.
DEMO = {
    "Fix all": "I don't think we can make it on Friday, sorry.",
    "Rewrite the text to be clearer": "Unfortunately, I don't think we'll be able to make it on Friday. Sorry about that!",
    "Rewrite the text to be significantly": "Sorry, we can't make Friday.",
    "Rewrite the text in a professional": "I regret that we will be unable to attend on Friday. My apologies.",
    "Rewrite the text in a friendly": "Ah sorry, don't think we can make it Friday!",
}


# A few typos to repair so the Fix diff has something to show.
TYPOS = {"Ths": "This", "tst": "test", "teh": "the", "recieve": "receive"}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        key = self.headers.get("x-goog-api-key", "")
        print(f"\n{self.path}  key={'set' if key else 'MISSING'}", flush=True)
        print(json.dumps(body, indent=2, ensure_ascii=False), flush=True)
        if not key or key == "bad-key":
            return self.reply(400, {"error": {"message": "API key not valid. Please pass a valid API key."}})
        model = self.path.split("/models/")[-1].split(":")[0]
        if not model.startswith("gemini-3"):
            return self.reply(404, {"error": {"message": f"models/{model} is not found for API version v1beta."}})
        system = body["systemInstruction"]["parts"][0]["text"]
        text = body["contents"][0]["parts"][0]["text"].removeprefix("TEXT:\n")
        out = next((v for k, v in DEMO.items() if system.startswith(k)), text)
        if system.startswith("Fix all") and "friday" not in text.lower():
            out = text[:1].upper() + text[1:].replace(" i ", " I ").replace("dont", "don't").rstrip(".") + "."
            for typo, fix in TYPOS.items():
                out = out.replace(typo, fix)
        self.reply(200, {"candidates": [{"content": {"parts": [{"text": json.dumps({"rewritten": out})}]}}]})

    def reply(self, status, obj):
        data = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f"mock Gemini on :{port}", flush=True)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
