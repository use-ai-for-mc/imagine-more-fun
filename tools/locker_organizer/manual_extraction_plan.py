#!/usr/bin/env python3
"""Build user-assisted extraction batches for protected nested items.

The organizer must not move items out of unmanaged shulker boxes by itself.
This script turns the read-only locker export into human-sized extraction
batches:

1. The user prepares an empty managed target container, such as
   ``Multipliers 01``.
2. The user manually extracts at most 27 listed items from old containers into
   the main inventory.
3. The executor can later move those main-inventory items into the managed
   target container.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import analyze_lockers
import classify_by_user_rules


TARGET_BY_RULE = {
    "pin_pack": "Pin Packs",
    "pin": "Pins",
    "multiplier": "Multipliers",
    "ride_multiplier_voucher": "Multipliers",
    "cap": "Wearables",
    "ears": "Wearables",
    "hat": "Wearables",
    "headband": "Wearables",
    "helmet": "Wearables",
    "loungefly": "Wearables",
    "mask": "Wearables",
    "profile_outfit": "Wearables",
    "the_cat": "Pets",
    "the_llama": "Pets",
    "plushie": "Pets",
    "puffle": "Pets",
    "the_shibe": "Pets",
    "shoulder_pet": "Pets",
    "pokeball": "Pets",
    "redeemable_title": "Titles",
    "chat_emoji": "Trails Emotes Warp",
    "trail": "Trails Emotes Warp",
    "warp_effect": "Trails Emotes Warp",
    "boba_tea": "Food",
    "dole_whip": "Food",
    "album": "Art Albums Posters",
    "concept_art": "Art Albums Posters",
    "profile_background": "Art Albums Posters",
    "poster": "Art Albums Posters",
    "brickhead": "Toys Decor",
    "button": "Toys Decor",
    "funko_pop": "Toys Decor",
    "toy_soldier": "Toys Decor",
    "stocking": "Toys Decor",
    "tsum_tsum": "Toys Decor",
    "blaster": "Wands Tools",
    "wand": "Wands Tools",
}

TARGET_PRIORITY = [
    "Multipliers",
    "Pin Packs",
    "Pins",
    "Wearables",
    "Pets",
    "Titles",
    "Trails Emotes Warp",
    "Food",
    "Art Albums Posters",
    "Toys Decor",
    "Wands Tools",
]


@dataclass(frozen=True)
class SourceContainer:
    path: str
    locker: int
    slot: int
    name: str
    managed: bool


@dataclass(frozen=True)
class Candidate:
    target: str
    rule_key: str
    rule_label: str
    name: str
    item_id: str
    count: int
    locker: int
    source_slot: int
    source_container_name: str
    nested_slot: int
    path: str
    source_path: str


def iter_nodes(nodes: Iterable[dict[str, Any]], locker: int, parent_path: str | None = None) -> Iterable[dict[str, Any]]:
    for node in nodes:
        out = dict(node)
        out["locker"] = locker
        out["parentPath"] = parent_path
        yield out
        if "container" in node:
            yield from iter_nodes(node["container"].get("items", []), locker, node["path"])


def source_containers(export: dict[str, Any]) -> dict[str, SourceContainer]:
    containers: dict[str, SourceContainer] = {}
    for locker in export.get("lockers", []):
        locker_number = int(locker["locker"])
        for node in iter_nodes(locker.get("items", []), locker_number):
            if "container" not in node:
                continue
            containers[node["path"]] = SourceContainer(
                path=node["path"],
                locker=locker_number,
                slot=int(node["slot"]),
                name=str(node["name"]),
                managed=analyze_lockers.managed_container_role(str(node["name"])) is not None,
            )
    return containers


def nested_slot_from_path(path: str) -> int:
    match = re.search(r"/container/(\d+)$", path)
    if not match:
        raise ValueError(f"not a direct nested item path: {path}")
    return int(match.group(1))


def candidate_for_node(node: dict[str, Any], sources: dict[str, SourceContainer]) -> Candidate | None:
    parent_path = node.get("parentPath")
    if parent_path is None:
        return None
    source = sources.get(parent_path)
    if source is None or source.managed:
        return None

    item = classify_by_user_rules.FlatItem(
        name=str(node["name"]),
        item_id=str(node["itemId"]),
        count=int(node.get("count", 1)),
        path=str(node["path"]),
        location="",
    )
    rule = classify_by_user_rules.classify(item)
    if rule is None:
        return None
    target = TARGET_BY_RULE.get(rule.key)
    if target is None:
        return None

    return Candidate(
        target=target,
        rule_key=rule.key,
        rule_label=rule.label,
        name=item.name,
        item_id=item.item_id,
        count=item.count,
        locker=source.locker,
        source_slot=source.slot,
        source_container_name=source.name,
        nested_slot=nested_slot_from_path(item.path),
        path=item.path,
        source_path=source.path,
    )


def collect_candidates(export: dict[str, Any]) -> list[Candidate]:
    sources = source_containers(export)
    candidates: list[Candidate] = []
    for locker in export.get("lockers", []):
        locker_number = int(locker["locker"])
        for node in iter_nodes(locker.get("items", []), locker_number):
            candidate = candidate_for_node(node, sources)
            if candidate is not None:
                candidates.append(candidate)
    return candidates


def target_sort_key(target: str) -> tuple[int, str]:
    try:
        return (TARGET_PRIORITY.index(target), target)
    except ValueError:
        return (len(TARGET_PRIORITY), target)


def candidate_sort_key(candidate: Candidate) -> tuple[int, int, int, str, str]:
    return (
        candidate.locker,
        candidate.source_slot,
        candidate.nested_slot,
        candidate.name.casefold(),
        candidate.path,
    )


def batch_candidates(candidates: list[Candidate], batch_size: int) -> dict[str, list[list[Candidate]]]:
    grouped: dict[str, list[Candidate]] = defaultdict(list)
    for candidate in candidates:
        grouped[candidate.target].append(candidate)

    batches: dict[str, list[list[Candidate]]] = {}
    for target, rows in grouped.items():
        rows.sort(key=candidate_sort_key)
        batches[target] = [rows[index : index + batch_size] for index in range(0, len(rows), batch_size)]
    return dict(sorted(batches.items(), key=lambda item: target_sort_key(item[0])))


def summarize_candidates(candidates: list[Candidate]) -> dict[str, Any]:
    by_target = Counter(candidate.target for candidate in candidates)
    by_rule = Counter(candidate.rule_key for candidate in candidates)
    by_source = Counter(candidate.source_path for candidate in candidates)
    return {
        "candidateItems": len(candidates),
        "targets": dict(sorted(by_target.items(), key=lambda item: target_sort_key(item[0]))),
        "rules": dict(sorted(by_rule.items())),
        "sourceContainers": len(by_source),
    }


def write_markdown(export_path: Path, out_path: Path, export: dict[str, Any], batches: dict[str, list[list[Candidate]]]) -> None:
    all_candidates = [candidate for target_batches in batches.values() for batch in target_batches for candidate in batch]
    summary = summarize_candidates(all_candidates)
    lines: list[str] = [
        "# Manual Extraction Plan",
        "",
        f"- Source: `{export_path}`",
        f"- Source generated at: `{export.get('generatedAt')}`",
        f"- Candidate nested items: `{summary['candidateItems']}`",
        f"- Source unmanaged containers: `{summary['sourceContainers']}`",
        "- Batch size: `27` main-inventory slots",
        "",
        "Use one target container at a time. Prepare an empty managed container with the shown name plus a two-digit suffix, for example `Multipliers 01`.",
        "For each batch, manually move exactly the listed items into the 27 main inventory slots, keeping the hotbar unchanged. Then the executor can move those items into the target container.",
        "",
        "## Summary By Target",
        "",
        "| Target container prefix | Items | Batches |",
        "|---|---:|---:|",
    ]

    for target, target_batches in batches.items():
        item_count = sum(len(batch) for batch in target_batches)
        lines.append(f"| `{target}` | {item_count} | {len(target_batches)} |")

    for target, target_batches in batches.items():
        lines.extend(["", f"## {target}", ""])
        lines.append(f"Prepare `{target} 01`. If it fills, continue with `{target} 02`, then `{target} 03`.")

        for batch_index, batch in enumerate(target_batches, start=1):
            lines.extend(["", f"### Batch {batch_index} ({len(batch)} items)", ""])
            lines.append("| /pv | Source slot | Source container | Inner slot | Item | Count | Rule |")
            lines.append("|---:|---:|---|---:|---|---:|---|")
            for row in batch:
                container_name = row.source_container_name.replace("|", "/")
                item_name = row.name.replace("|", "/")
                lines.append(
                    f"| {row.locker} | {row.source_slot} | {container_name} | "
                    f"{row.nested_slot} | {item_name} | {row.count} | {row.rule_key} |"
                )

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export_json", type=Path)
    parser.add_argument("--out", type=Path, default=Path("build/locker-organizer/manual-extraction-plan.md"))
    parser.add_argument("--batch-size", type=int, default=27)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    export = json.loads(args.export_json.read_text(encoding="utf-8"))
    candidates = collect_candidates(export)
    batches = batch_candidates(candidates, args.batch_size)
    write_markdown(args.export_json, args.out, export, batches)
    print(args.out)
    print(json.dumps(summarize_candidates(candidates), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
