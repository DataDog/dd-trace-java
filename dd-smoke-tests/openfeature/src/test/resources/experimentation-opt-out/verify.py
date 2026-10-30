#!/usr/bin/env python3
"""Verify the native policy fixture against the pinned shared contract corpus."""
import argparse
import hashlib
import json
from pathlib import Path

SOURCE_SHA256 = "4c4628ae30217bd2ead2ef34144f43e0163f9916ff0c8accae0847391dff64a6"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="pinned browser-proposal.jsonl")
    args = parser.parse_args()
    source = args.source.read_bytes()
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        parser.error("source corpus does not match the pinned SHA-256")
    rows = [json.loads(line) for line in source.splitlines()]
    expected = [
        {
            key: value[len("policy/"):] if key == "id" else value
            for key, value in row.items()
            if key not in ("kind", "decision_ids")
        }
        for row in rows
        if row["kind"] == "policy"
    ]
    actual = json.loads(Path(__file__).with_name("cases.json").read_text())
    if len(expected) != 90 or len({row["id"] for row in expected}) != 90:
        parser.error("expected 90 unique policy cases")
    if actual != expected:
        parser.error("cases.json differs from the pinned policy extraction")
    print("Verified all 90 policy cases against the pinned source")


if __name__ == "__main__":
    main()
