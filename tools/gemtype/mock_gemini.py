#!/usr/bin/env python3
"""Tiny stand-in for Gemini's generateContent endpoint, for emulator testing
without a real API key. Debug builds only:

    python3 tools/gemtype/mock_gemini.py     # listens on :8765
    adb reverse tcp:8765 tcp:8765
    make install ABI=x86_64 GRADLE_ARGS=-Pgemtype.apiBase=http://localhost:8765/v1beta/models

Each request body is printed so you can check what the app sends (the key
itself is never printed). Replies use the same structured-JSON shape the app
asks Gemini for: {"rewritten": "..."}, or {"corrections": [...]} for the proofreader
(request schema has "corrections"): fixed demo corrections for "i dont think we can make it friday sorry",
a few common typos otherwise, an empty list for text with none. Failure cases for the Test key button:
"native script" prompts -> a fixed sentence in the language named in the prompt, "Translate" -> English;
key "bad-key" -> 400 invalid key; any model id not starting "gemini-3" -> 404.
Failure modes keyed by the API key (a key that merely contains the word, e.g. AIzaMOCKRATE000000000000000000000000000):
RATE -> 429, BUSY -> 503, LOCATION -> 400 "User location is not supported" (what Google says to unsupported regions, VPNs included), SLOW -> answers only after 40 s (MOCK_SLOW=n to change), GARBAGE -> 200 with a body that is not JSON.
MOCK_DELAY=2 python3 tools/gemtype/mock_gemini.py answers after 2 s (to see spinners).
"""
import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Canned rewrites keyed by the first words of each action's prompt.
DEMO = {
    "Fix all": "I don't think we can make it on Friday, sorry.",
    "Rewrite the text to be clearer": "Unfortunately, I don't think we'll be able to make it on Friday. Sorry about that!",
    "Rewrite the text to be significantly": "Sorry, we can't make Friday.",
    "Rewrite the text in a professional": "I regret that we will be unable to attend on Friday. My apologies.",
    "Rewrite the text in a friendly": "Ah sorry, don't think we can make it Friday!",
}


# "Native script" requests name the language in the system prompt ("The text is Bangla written ...").
NATIVE = {
    "bangla": "আমি কাল আসবো না।", "bengali": "আমি কাল আসবো না।",
    "hindi": "मैं कल आऊँगा।", "urdu": "میں کل آؤں گا۔", "nepali": "म भोलि आउँछु।",
    "tamil": "நான் நாளை வருவேன்.", "telugu": "నేను రేపు వస్తాను.",
}

# A few typos to repair so the Fix diff has something to show.
TYPOS = {"Ths": "This", "tst": "test", "teh": "the", "recieve": "receive"}


# Proofreader answers. Each: (regex over the text, replacement, explanation, type). Only the first
# match of each rule is reported, and "original" is copied from the text, as the real model does.
CHECKS = [
    (r"(?i)\bit friday sorry\b", "it on Friday, sorry.", "Missing 'on' and punctuation.", "punctuation"),
    (r"^i\b", "I", "The pronoun 'I' is always capital.", "grammar"),
    (r"\bdont\b", "don't", "Missing apostrophe.", "spelling"),
    (r"\bim\b", "I'm", "Missing apostrophe.", "spelling"),
    (r"\bcant\b", "can't", "Missing apostrophe.", "spelling"),
    (r"\bwont\b", "won't", "Missing apostrophe.", "spelling"),
    (r"\b(Ths|ths)\b", "This", "Misspelled word.", "spelling"),
    (r"\btst\b", "test", "Misspelled word.", "spelling"),
    (r"\bteh\b", "the", "Misspelled word.", "spelling"),
    (r"\brecieve\b", "receive", "'i' before 'e' except after 'c'.", "spelling"),
]


def corrections_for(text):
    """Deterministic stand-in for the proofreader; corrections never overlap."""
    found, used = [], []
    for pattern, replacement, why, kind in CHECKS:
        m = re.search(pattern, text)
        if m and not any(m.start() < e and m.end() > s for s, e in used):
            used.append((m.start(), m.end()))
            found.append((m.start(), {"original": m.group(0), "replacement": replacement, "explanation": why, "type": kind}))
    return [c for _, c in sorted(found, key=lambda x: x[0])]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        key = self.headers.get("x-goog-api-key", "")
        print(f"\n{self.path}  key={'set' if key else 'MISSING'}", flush=True)
        print(json.dumps(body, indent=2, ensure_ascii=False), flush=True)
        time.sleep(float(os.environ.get("MOCK_DELAY", "0")))
        if "SLOW" in key:
            time.sleep(float(os.environ.get("MOCK_SLOW", "40")))
        if "RATE" in key:
            return self.reply(429, {"error": {"message": "Resource has been exhausted (e.g. check quota)."}})
        if "LOCATION" in key:
            return self.reply(400, {"error": {"code": 400, "status": "FAILED_PRECONDITION",
                                              "message": "User location is not supported for the API use."}})
        if "BUSY" in key:
            return self.reply(503, {"error": {"message": "The model is overloaded. Please try again later."}})
        if "GARBAGE" in key:
            return self.reply_raw(200, b"<html>not json {{{")
        if not key or key == "bad-key":
            return self.reply(400, {"error": {"message": "API key not valid. Please pass a valid API key."}})
        model = self.path.split("/models/")[-1].split(":")[0]
        if not model.startswith("gemini-3"):
            return self.reply(404, {"error": {"message": f"models/{model} is not found for API version v1beta."}})
        system = body["systemInstruction"]["parts"][0]["text"]
        schema = body.get("generationConfig", {}).get("responseSchema", {})
        if "corrections" in schema.get("properties", {}):
            text = body["contents"][0]["parts"][0]["text"].removeprefix("TEXT TO CHECK:\n")
            answer = {"corrections": corrections_for(text)}
            return self.reply(200, {"candidates": [{"content": {"parts": [{"text": json.dumps(answer)}]}}]})
        text = body["contents"][0]["parts"][0]["text"].removeprefix("TEXT:\n")
        out = next((v for k, v in DEMO.items() if system.startswith(k)), text)
        if "native script" in system:
            language = system.split("The text is ")[1].split(" written")[0].lower()
            out = NATIVE.get(language, "[" + language + "] " + text)
        elif system.startswith("Translate"):
            out = "I will not come tomorrow."
        if system.startswith("Fix all") and "friday" not in text.lower():
            out = text[:1].upper() + text[1:].replace(" i ", " I ").replace("dont", "don't").rstrip(".") + "."
            for typo, fix in TYPOS.items():
                out = out.replace(typo, fix)
        self.reply(200, {"candidates": [{"content": {"parts": [{"text": json.dumps({"rewritten": out})}]}}]})

    def reply(self, status, obj):
        self.reply_raw(status, json.dumps(obj).encode())

    def reply_raw(self, status, data):
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
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
