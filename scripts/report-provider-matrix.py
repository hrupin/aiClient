from collections import Counter
from pathlib import Path
import re
import sys
from xml.etree import ElementTree


PROVIDERS = ("OPENAI", "DEEPSEEK", "XAI", "ZAI", "KIMI", "PERPLEXITY")
CASES = {
    "bufferedWithoutSchema": "Обычный / текст",
    "bufferedWithSchema": "Обычный / JSON Schema",
    "streamingWithoutSchema": "Поток / текст",
    "streamingWithSchema": "Поток / JSON Schema",
}


def summarize(case):
    failed = case.find("failure")
    if failed is None:
        failed = case.find("error")
    if failed is not None:
        return "FAIL", failed.get("message", "").split("\n", 1)[0]
    if case.find("skipped") is not None:
        return "SKIP", ""
    return "PASS", (case.findtext("system-out") or "").strip()


def read_results(directory):
    results = {}
    for report in sorted(directory.glob("TEST-*.xml")):
        for case in ElementTree.parse(report).getroot().iter("testcase"):
            match = re.fullmatch(r"([^\[]+)\[([^\]]+)\]", case.get("name", ""))
            if match:
                method, provider = match.groups()
                if method in CASES and provider in PROVIDERS:
                    results[provider, method] = summarize(case)
    return results


def print_table(results):
    rows = [["Провайдер", "Режим", "Статус", "Результат"]]
    totals = Counter()
    for provider in PROVIDERS:
        for method, title in CASES.items():
            status, result = results.get((provider, method), ("NOT RUN", ""))
            totals[status] += 1
            result = " ".join(result.split())
            if len(result) > 80:
                result = result[:79] + "…"
            rows.append([provider, title, status, result or "—"])

    widths = [max(map(len, column)) for column in zip(*rows)]
    print("\nИтог тестов")
    for index, row in enumerate(rows):
        print(" | ".join(value.ljust(width) for value, width in zip(row, widths)).rstrip())
        if index == 0:
            print("-+-".join("-" * width for width in widths))
    print(
        f"\nВсего {sum(totals.values())} — PASS {totals['PASS']}, FAIL {totals['FAIL']}, "
        f"SKIP {totals['SKIP']}, NOT RUN {totals['NOT RUN']}",
    )


if __name__ == "__main__":
    print_table(read_results(Path(sys.argv[1])))
