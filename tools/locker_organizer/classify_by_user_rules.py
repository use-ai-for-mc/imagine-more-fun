#!/usr/bin/env python3
"""Classify non-shulker locker items with user-provided name rules.

This report may inspect nested contents, but movement planning treats items
inside unmanaged shulker boxes as protected read-only inventory.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable


@dataclass(frozen=True)
class FlatItem:
    name: str
    item_id: str
    count: int
    path: str
    location: str


@dataclass(frozen=True)
class Rule:
    key: str
    label: str
    predicate: Callable[[str, str], bool]


def normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip()).casefold()


def strip_trailing_parentheticals(value: str) -> str:
    current = value.strip()
    while True:
        new_value = re.sub(r"\s*[/ ]*\([^()]*\)\s*$", "", current).strip()
        if new_value == current:
            return current
        current = new_value


def starts_with(prefix: str) -> Callable[[str, str], bool]:
    prefix_norm = normalize(prefix)
    return lambda raw, clean: normalize(raw).startswith(prefix_norm)


def ends_with(suffix: str, *, use_raw: bool = False) -> Callable[[str, str], bool]:
    suffix_norm = normalize(suffix)
    return lambda raw, clean: normalize(raw if use_raw else clean).endswith(suffix_norm)


def exact(value: str) -> Callable[[str, str], bool]:
    value_norm = normalize(value)
    return lambda raw, clean: normalize(raw) == value_norm


def pokeball_rule(raw: str, clean: str) -> bool:
    text = normalize(raw)
    return text.startswith(("pokeball", "great ball", "love ball", "ultraball", "ultra ball"))


def pin_pack_rule(raw: str, clean: str) -> bool:
    text = normalize(raw)
    return text.startswith("pin pack -") or text.startswith("⭐ pin pack -")


def trail_rule(raw: str, clean: str) -> bool:
    # Supports names like "Glitch Trail (Click to redeem)" and
    # "/ Glitch Trail / (Click to redeem)".
    text = normalize(raw).rstrip()
    return bool(re.search(r"trail\s*/?\s*\(click to redeem\)\s*$", text))


RULES: list[Rule] = [
    Rule("pin_pack", "Starts with Pin Pack - / ⭐ Pin Pack -", pin_pack_rule),
    Rule("album", "Ends with Album", ends_with("Album")),
    Rule("concept_art", "Ends with Concept Art", ends_with("Concept Art")),
    Rule("profile_background", "Ends with Profile Background", ends_with("Profile Background")),
    Rule("blaster", "Ends with Blaster", ends_with("Blaster")),
    Rule("brickhead", "Ends with Brickhead", ends_with("Brickhead")),
    Rule("button", "Ends with Button", ends_with("Button")),
    Rule("pokeball", "Starts with Pokeball / Great Ball / Love Ball / Ultraball", pokeball_rule),
    Rule("cap", "Ends with Cap", ends_with("Cap")),
    Rule("the_cat", "Ends with the Cat", ends_with("the Cat")),
    Rule("ears", "Ends with Ears", ends_with("Ears")),
    Rule(
        "chat_emoji",
        "Ends with Redeemable Chat Display Emoji (Right Click)",
        ends_with("Redeemable Chat Display Emoji (Right Click)", use_raw=True),
    ),
    Rule("hat", "Ends with Hat", ends_with("Hat")),
    Rule("headband", "Ends with Headband", ends_with("Headband")),
    Rule("helmet", "Ends with Helmet", ends_with("Helmet")),
    Rule("the_llama", "Ends with the Llama", ends_with("the Llama")),
    Rule("loungefly", "Ends with Loungefly", ends_with("Loungefly")),
    Rule("mask", "Ends with Mask", ends_with("Mask")),
    Rule("multiplier", "Ends with Multiplier", ends_with("Multiplier")),
    Rule("profile_outfit", "Ends with Profile Outfit", ends_with("Profile Outfit")),
    Rule("shoulder_pet", "Ends with Shoulder Pet", ends_with("Shoulder Pet")),
    Rule("pin", "Ends with Pin", ends_with("Pin")),
    Rule("plushie", "Ends with Plushie", ends_with("Plushie")),
    Rule("funko_pop", "Ends with Funko Pop", ends_with("Funko Pop")),
    Rule("poster", "Ends with Poster", ends_with("Poster")),
    Rule("puffle", "Ends with Puffle", ends_with("Puffle")),
    Rule("the_shibe", "Ends with the Shibe", ends_with("the Shibe")),
    Rule("toy_soldier", "Ends with Toy Soldier", ends_with("Toy Soldier")),
    Rule("stocking", "Ends with Stocking", ends_with("Stocking")),
    Rule("boba_tea", "Ends with Boba Tea", ends_with("Boba Tea")),
    Rule(
        "redeemable_title",
        "Ends with Redeemable Title (Right click)",
        ends_with("Redeemable Title (Right click)", use_raw=True),
    ),
    Rule("trail", "Ends with Trail (Click to redeem)", trail_rule),
    Rule("tsum_tsum", "Ends with Tsum Tsum", ends_with("Tsum Tsum")),
    Rule(
        "ride_multiplier_voucher",
        "Ride Multiplier Voucher (Right click to redeem)",
        exact("Ride Multiplier Voucher (Right click to redeem)"),
    ),
    Rule("warp_effect", "Ends with Warp Effect voucher", ends_with("Warp Effect voucher", use_raw=True)),
    Rule("dole_whip", "Ends with Dole Whip", ends_with("Dole Whip")),
    Rule("wand", "Ends with Wand", ends_with("Wand")),
]


def iter_items(nodes: Iterable[dict[str, Any]], ancestors: tuple[str, ...]) -> Iterable[FlatItem]:
    for node in nodes:
        item = FlatItem(
            name=node["name"],
            item_id=node["itemId"],
            count=int(node.get("count", 1)),
            path=node["path"],
            location=" > ".join(ancestors + (node["name"],)),
        )
        yield item
        if "container" in node:
            yield from iter_items(node["container"].get("items", []), ancestors + (node["name"],))


def is_shulker_box(item: FlatItem) -> bool:
    return item.item_id.startswith("minecraft:") and item.item_id.endswith("_shulker_box")


def classify(item: FlatItem) -> Rule | None:
    if is_shulker_box(item):
        return None
    clean = strip_trailing_parentheticals(item.name)
    for rule in RULES:
        if rule.predicate(item.name, clean):
            return rule
    return None


def group_items(items: list[FlatItem]) -> dict[str, Any]:
    categorized: dict[str, dict[str, Any]] = {
        rule.key: {"rule": rule, "items": defaultdict(list)} for rule in RULES
    }
    remaining: dict[str, list[FlatItem]] = defaultdict(list)
    skipped_shulkers: dict[str, list[FlatItem]] = defaultdict(list)

    for item in items:
        if is_shulker_box(item):
            skipped_shulkers[item.name].append(item)
            continue

        rule = classify(item)
        if rule is None:
            remaining[item.name].append(item)
        else:
            categorized[rule.key]["items"][item.name].append(item)

    return {
        "categorized": categorized,
        "remaining": remaining,
        "skipped_shulkers": skipped_shulkers,
    }


def summarize_group(group: dict[str, list[FlatItem]]) -> list[dict[str, Any]]:
    rows = []
    for name, entries in group.items():
        rows.append(
            {
                "name": name,
                "entries": len(entries),
                "totalCount": sum(item.count for item in entries),
                "examples": [item.location for item in entries[:5]],
            }
        )
    rows.sort(key=lambda row: (-row["entries"], row["name"].casefold()))
    return rows


def markdown_table(rows: list[dict[str, Any]]) -> list[str]:
    lines = ["| Item | Entries | Total count | Example locations |", "|---|---:|---:|---|"]
    if not rows:
        lines.append("| _none_ | 0 | 0 |  |")
        return lines

    for row in rows:
        examples = "<br>".join(example.replace("|", "/") for example in row["examples"])
        lines.append(f"| {row['name']} | {row['entries']} | {row['totalCount']} | {examples} |")
    return lines


def write_report(export_path: Path, out_path: Path, grouped: dict[str, Any], item_count: int) -> None:
    categorized = grouped["categorized"]
    remaining = grouped["remaining"]
    skipped_shulkers = grouped["skipped_shulkers"]

    matched_entries = 0
    matched_names = 0
    for section in categorized.values():
        matched_entries += sum(len(entries) for entries in section["items"].values())
        matched_names += len(section["items"])

    lines: list[str] = [
        "# User Rule Classification",
        "",
        f"- Source: `{export_path}`",
        f"- Total scanned items including containers: `{item_count}`",
        f"- Skipped shulker-box items: `{sum(len(v) for v in skipped_shulkers.values())}`",
        f"- Matched non-shulker entries: `{matched_entries}`",
        f"- Matched unique item names: `{matched_names}`",
        f"- Remaining non-shulker entries: `{sum(len(v) for v in remaining.values())}`",
        f"- Remaining unique item names: `{len(remaining)}`",
        "",
        "Rules are applied in the order provided. They only apply when `itemId` is not `*_shulker_box`.",
        "Nested contents are diagnostic: movement planning must not remove items from unmanaged shulker boxes.",
        "",
        "## Summary By Rule",
        "",
        "| Rule | Unique names | Entries | Total count |",
        "|---|---:|---:|---:|",
    ]

    for rule in RULES:
        rows = summarize_group(categorized[rule.key]["items"])
        lines.append(
            f"| {rule.label} | {len(rows)} | "
            f"{sum(row['entries'] for row in rows)} | {sum(row['totalCount'] for row in rows)} |"
        )

    for rule in RULES:
        rows = summarize_group(categorized[rule.key]["items"])
        lines.extend(["", f"## {rule.label}", ""])
        lines.extend(markdown_table(rows))

    lines.extend(["", "## Remaining Non-Shulker Items", ""])
    lines.extend(markdown_table(summarize_group(remaining)))

    lines.extend(["", "## Skipped Shulker-Box Items", ""])
    lines.extend(markdown_table(summarize_group(skipped_shulkers)))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export_json", type=Path)
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("build/locker-organizer/user-rule-classification.md"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    export = json.loads(args.export_json.read_text(encoding="utf-8"))
    items: list[FlatItem] = []
    for locker in export.get("lockers", []):
        items.extend(iter_items(locker.get("items", []), (f"Locker #{locker['locker']}",)))

    grouped = group_items(items)
    write_report(args.export_json, args.out, grouped, len(items))
    print(args.out)
    print(
        json.dumps(
            {
                "items": len(items),
                "matchedEntries": sum(
                    len(entries)
                    for section in grouped["categorized"].values()
                    for entries in section["items"].values()
                ),
                "remainingEntries": sum(len(entries) for entries in grouped["remaining"].values()),
                "skippedShulkers": sum(len(entries) for entries in grouped["skipped_shulkers"].values()),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
