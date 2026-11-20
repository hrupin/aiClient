from collections import Counter
from pathlib import Path
import re
import sys
from xml.etree import ElementTree


CASES = {
    "bufferedWithoutSchema": "Обычный / текст",
    "bufferedWithSchema": "Обычный / JSON Schema",
    "streamingWithoutSchema": "Поток / текст",
    "streamingWithSchema": "Поток / JSON Schema",
}
CASES.update({
    "mcp" + method[0].upper() + method[1:]: "MCP / " + title
    for method, title in CASES.items()
})


def summarize(case):
    failed = case.find("failure")
    if failed is None:
        failed = case.find("error")
    if failed is not None:
        message = failed.get("message", "").split("\n", 1)[0]
        return "FAIL", message.partition(": ")[2] or message
    if case.find("skipped") is not None:
        return "SKIP", ""
    return "PASS", (case.findtext("system-out") or "").strip()


def read_results(directory):
    results = {}
    for report in sorted(directory.glob("TEST-*.xml")):
        for case in ElementTree.parse(report).getroot().iter("testcase"):
            match = re.fullmatch(r"([^\[]+)\[([^\]]+)\]", case.get("name", ""))
            if match:
                method, label = match.groups()
                provider, separator, model = label.partition("/")
                if method in CASES and separator:
                    results[provider, model, method] = summarize(case)
    return results


def print_table(results):
    rows = [["Провайдер", "Модель", "Режим", "Статус", "Результат"]]
    totals = Counter()
    for provider, model in dict.fromkeys((provider, model) for provider, model, _ in results):
        for method, title in CASES.items():
            status, result = results.get((provider, model, method), ("NOT RUN", ""))
            totals[status] += 1
            result = " ".join(result.split())
            if len(result) > 80:
                result = result[:79] + "…"
            rows.append([provider, model, title, status, result or "—"])

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
    results = read_results(Path(sys.argv[1]))
    print_table(results)
    sys.exit(0 if results else 1)
