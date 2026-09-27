#!/usr/bin/env python3
"""Character error rate for Korean STT output.

Usage: cer.py REFERENCE.txt HYPOTHESIS.txt
Normalisation: Unicode NFC, lower-case, drop whitespace and punctuation (Korean spacing is
inconsistent, so CER is computed on characters only, as in the Whisper paper).
Prints: cer substitutions+deletions+insertions ref_len
"""
import sys
import unicodedata


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFC", text).lower()
    return "".join(ch for ch in text if unicodedata.category(ch)[0] in ("L", "N"))


def edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def main() -> None:
    ref = normalize(open(sys.argv[1], encoding="utf-8").read())
    hyp = normalize(open(sys.argv[2], encoding="utf-8").read())
    dist = edit_distance(ref, hyp)
    print(f"{dist / max(1, len(ref)):.4f} {dist} {len(ref)}")


if __name__ == "__main__":
    main()
