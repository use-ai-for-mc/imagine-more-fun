#!/usr/bin/env python3
"""Analyze an ImagineFun locker export without moving items.

The analyzer intentionally produces a stable, minimal-diff model:

- containers are category homes;
- lockers are physical capacity, not fixed category roles;
- Locker #1 is treated as preferred staging space;
- only explicitly managed containers are automatic movement homes;
- repeated generic container names become rename candidates;
- scattered duplicate items are reported, but protected nested items are not
  future move-planner candidates unless their parent container is managed.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


CATEGORY_LABELS = {
    "titles": "Titles",
    "multipliers": "Multipliers",
    "pins": "Pins",
    "food_drinks": "Food / Drinks",
    "hats_wearables": "Hats / Wearables",
    "pets_plushies": "Pets / Plushies",
    "tools_wands_weapons": "Tools / Wands / Weapons",
    "furniture_decor": "Furniture / Decor",
    "event_collectibles": "Event Collectibles",
    "labels": "Labels",
    "misc": "Misc",
}

GENERIC_CONTAINER_NAMES = {
    "popcorn bucket",
    "imaginefun suitcase",
    "treasure chest",
    "backpack",
    "multi",
    "andy's toy box",
    "pin collector book",
    "pin collector case",
    "wilderness explorer backpack",
    "hatbox ghost's hatbox",
}

EXPLICIT_CONTAINER_ROLES = {
    "titles": "titles",
    "title": "titles",
    "trails, emotes, warp": "misc",
    "caps, hats, ears": "hats_wearables",
    "instruments": "misc",
    "patches and pins": "pins",
    "food": "food_drinks",
    "coffee": "food_drinks",
    "boba": "food_drinks",
    "multiplier": "multipliers",
    "multipliers": "multipliers",
    "multi": "multipliers",
    "xmas toys i": "event_collectibles",
    "toy soliders": "event_collectibles",
    "toy soldiers": "event_collectibles",
    "funko pop": "pets_plushies",
}

MANAGED_CONTAINER_ROLES = {
    "pin packs": "pins",
    "pins": "pins",
    "wearables": "hats_wearables",
    "pets": "pets_plushies",
    "multipliers": "multipliers",
    "titles": "titles",
    "trails emotes warp": "misc",
    "food": "food_drinks",
    "art albums posters": "event_collectibles",
    "toys decor": "furniture_decor",
    "wands tools": "tools_wands_weapons",
    "misc": "misc",
}

THEME_TOKENS = [
    "70th",
    "mickey",
    "coffee",
    "boba",
    "christmas",
    "halloween",
    "valentine",
    "easter",
    "hanukkah",
    "kwanzaa",
    "star",
    "autopia",
    "indy",
]


@dataclass(frozen=True)
class FlatItem:
    path: str
    name: str
    item_id: str
    count: int
    slot: int
    locker: int
    ancestors: tuple[str, ...]
    is_container: bool
    direct_child_count: int

    @property
    def parent_path(self) -> str:
        parts = self.path.split("/")
        if "/container/" in self.path:
            return "/".join(parts[:-2])
        return f"locker/{self.locker}"

    @property
    def depth(self) -> int:
        return max(0, len(self.ancestors) - 1)


def normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().casefold())


def canonical_item_name(value: str) -> str:
    value = normalize_text(value)
    value = re.sub(r"\s+\(right click(?: to redeem)?\)$", "", value)
    value = re.sub(r"\s+\(click to redeem\)$", "", value)
    return value


def classify_name(name: str, item_id: str = "") -> str:
    text = normalize_text(name)

    def has_word(*words: str) -> bool:
        return any(re.search(rf"\b{re.escape(word)}\b", text) for word in words)

    if "item label" in text:
        return "labels"
    if "redeemable title" in text or "chat display emoji" in text:
        return "titles"
    if "multiplier" in text or "ride multiplier voucher" in text:
        return "multipliers"
    if "pin pack" in text or re.search(r"(^| )pin($| )", text) or "patches and pins" in text:
        return "pins"
    if any(token in text for token in [
        "coffee",
        "boba",
        "dole whip",
        "beignet",
        "waffle",
        "pancake",
        "cake",
        "cookie",
        "cocoa",
        "latke",
        "milk",
        "tea",
        "food",
        "drink",
        "munchling",
        "rice box",
        "candy apple",
        "ice cream",
    ]):
        return "food_drinks"
    if "headband" in text:
        return "hats_wearables"
    if any(token in text for token in [
        "pet",
        "plush",
        "plushie",
        "teddy",
        "puffle",
        "llama",
        "duck",
        "droid",
        "kermit",
        "shoulder",
        "figure",
    ]):
        return "pets_plushies"
    if (
        any(token in text for token in [
            "wand",
            "blaster",
            "launcher",
            "bow",
            "sword",
            "shield",
            "staff",
            "scepter",
            "oar",
            "brush",
            "hammer",
            "spear",
            "keyblade",
            "frisbee",
            "crossbow",
            "lazer",
            "laser",
            "gun",
        ])
        or has_word("cane")
    ):
        return "tools_wands_weapons"
    if (
        any(token in text for token in [
            "ears",
            "helmet",
            "outfit",
            "sweater",
            "wings",
            "glasses",
            "loungefly",
            "backpack",
            "belt",
            "crown",
            "halo",
            "headband",
        ])
        or has_word("hat", "cap")
    ):
        return "hats_wearables"
    if any(token in text for token in [
        "bed",
        "fridge",
        "lamp",
        "lantern",
        "ornament",
        "decor",
        "barrel",
        "plant",
        "chair",
        "table",
        "doombuggy",
    ]):
        return "furniture_decor"
    if any(token in text for token in [
        "christmas",
        "halloween",
        "valentine",
        "easter",
        "hanukkah",
        "kwanzaa",
        "70th",
        "anniversary",
        "nye",
        "winter",
        "spring",
        "stocking",
        "toy soldier",
        "toy solider",
    ]):
        return "event_collectibles"

    return "misc"


def iter_items(nodes: Iterable[dict[str, Any]], locker: int, ancestors: tuple[str, ...]) -> Iterable[FlatItem]:
    for node in nodes:
        container = node.get("container")
        item = FlatItem(
            path=node["path"],
            name=node["name"],
            item_id=node["itemId"],
            count=int(node.get("count", 1)),
            slot=int(node["slot"]),
            locker=locker,
            ancestors=ancestors,
            is_container=container is not None,
            direct_child_count=0 if container is None else int(container.get("nonEmptySlots", 0)),
        )
        yield item
        if container is not None:
            yield from iter_items(container.get("items", []), locker, ancestors + (item.name,))


def flatten_export(export: dict[str, Any]) -> list[FlatItem]:
    items: list[FlatItem] = []
    for locker in export.get("lockers", []):
        locker_number = int(locker["locker"])
        items.extend(iter_items(locker.get("items", []), locker_number, (f"Locker #{locker_number}",)))
    return items


def direct_children_by_parent(items: list[FlatItem]) -> dict[str, list[FlatItem]]:
    children: dict[str, list[FlatItem]] = defaultdict(list)
    for item in items:
        if item.depth > 0:
            children[item.parent_path].append(item)
    return children


def explicit_container_role(name: str) -> str | None:
    text = normalize_text(name)
    if text in EXPLICIT_CONTAINER_ROLES:
        return EXPLICIT_CONTAINER_ROLES[text]
    if "multiplier" in text:
        return "multipliers"
    if "title" in text:
        return "titles"
    if "pin" in text:
        return "pins"
    if "hat" in text or "ears" in text:
        return "hats_wearables"
    if "food" in text or "coffee" in text or "boba" in text:
        return "food_drinks"
    return None


def managed_container_role(name: str) -> str | None:
    text = normalize_text(name)
    match = re.fullmatch(r"(.+?)\s+(\d{2})", text)
    if not match:
        return None
    return MANAGED_CONTAINER_ROLES.get(match.group(1))


def infer_container_roles(items: list[FlatItem]) -> dict[str, dict[str, Any]]:
    children = direct_children_by_parent(items)
    roles: dict[str, dict[str, Any]] = {}

    for item in items:
        if not item.is_container:
            continue

        managed = managed_container_role(item.name)
        explicit = explicit_container_role(item.name)
        child_categories = Counter(
            classify_name(child.name, child.item_id)
            for child in children.get(item.path, [])
            if not child.is_container
        )
        total_children = sum(child_categories.values())
        top_category = None
        top_count = 0
        confidence = 0.0
        source = "contents"

        if child_categories:
            top_category, top_count = child_categories.most_common(1)[0]
            confidence = top_count / total_children

        role = managed or explicit
        if managed is not None:
            source = "managed_name"
            confidence = 1.0
        elif role is not None:
            source = "name"
            confidence = 1.0
        elif total_children >= 5 and confidence >= 0.60:
            role = top_category
        elif total_children >= 12 and confidence >= 0.45:
            role = top_category
        else:
            role = "misc"
            source = "fallback"

        roles[item.path] = {
            "path": item.path,
            "name": item.name,
            "locker": item.locker,
            "slot": item.slot,
            "role": role,
            "source": source,
            "confidence": round(confidence, 3),
            "directChildCount": item.direct_child_count,
            "freeSlotsEstimate": max(0, 27 - item.direct_child_count),
            "isManagedContainer": managed is not None,
            "isGenericName": normalize_text(item.name) in GENERIC_CONTAINER_NAMES,
            "childCategoryCounts": dict(child_categories),
            "themeHint": theme_hint(children.get(item.path, [])),
        }

    return roles


def theme_hint(children: list[FlatItem]) -> str | None:
    counts: Counter[str] = Counter()
    for child in children:
        text = normalize_text(child.name)
        for token in THEME_TOKENS:
            if token in text:
                counts[token] += 1
    if not counts:
        return None
    token, count = counts.most_common(1)[0]
    if count < 2:
        return None
    if token == "70th":
        return "70th"
    return token.title()


def stable_container_sort_key(role: dict[str, Any]) -> tuple[int, int, str]:
    # Later lockers are preferred storage. Slot order is stable inside a locker.
    return (-int(role["locker"]), int(role["slot"]), str(role["path"]))


def build_inferred_home_chains(roles: dict[str, dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    chains: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for role in roles.values():
        if role["role"] == "misc" and role["source"] == "fallback":
            continue
        chains[role["role"]].append(role)
    for role in list(chains):
        chains[role].sort(key=stable_container_sort_key)
    return dict(chains)


def build_managed_home_chains(roles: dict[str, dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    chains: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for role in roles.values():
        if not role["isManagedContainer"]:
            continue
        chains[role["role"]].append(role)
    for role in list(chains):
        chains[role].sort(key=stable_container_sort_key)
    return dict(chains)


def recommended_label(role: dict[str, Any], index: int) -> str:
    category = CATEGORY_LABELS.get(role["role"], "Misc").split(" / ")[0]
    hint = role.get("themeHint")
    if hint and role["role"] in {"pins", "food_drinks", "event_collectibles"}:
        return f"{category} {hint} {index:02d}"
    return f"{category} {index:02d}"


def rename_candidates(chains: dict[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    for role, containers in chains.items():
        if role == "misc":
            continue
        for index, container in enumerate(containers, start=1):
            if not container["isGenericName"]:
                continue
            if container["directChildCount"] < 5:
                continue
            candidates.append(
                {
                    "path": container["path"],
                    "locker": container["locker"],
                    "slot": container["slot"],
                    "currentName": container["name"],
                    "role": role,
                    "confidence": container["confidence"],
                    "directChildCount": container["directChildCount"],
                    "suggestedName": recommended_label(container, index),
                }
            )
    return candidates


def protected_nested_item_count(items: list[FlatItem], roles: dict[str, dict[str, Any]]) -> int:
    protected = 0
    for item in items:
        if item.is_container or item.depth == 0:
            continue
        parent = roles.get(item.parent_path)
        if parent is None or not parent["isManagedContainer"]:
            protected += 1
    return protected


def scattered_items(items: list[FlatItem]) -> list[dict[str, Any]]:
    groups: dict[str, list[FlatItem]] = defaultdict(list)
    for item in items:
        if item.is_container:
            continue
        groups[canonical_item_name(item.name)].append(item)

    scattered: list[dict[str, Any]] = []
    for _, group in groups.items():
        if len(group) < 2:
            continue
        parent_paths = {item.parent_path for item in group}
        lockers = {item.locker for item in group}
        if len(parent_paths) <= 1 and len(lockers) <= 1:
            continue
        representative = group[0]
        scattered.append(
            {
                "name": representative.name,
                "category": classify_name(representative.name, representative.item_id),
                "entries": len(group),
                "totalCount": sum(item.count for item in group),
                "lockerCount": len(lockers),
                "parentCount": len(parent_paths),
                "examples": [
                    {
                        "path": item.path,
                        "count": item.count,
                        "location": " > ".join(item.ancestors + (item.name,)),
                    }
                    for item in group[:5]
                ],
            }
        )

    scattered.sort(
        key=lambda row: (
            -int(row["entries"]),
            -int(row["lockerCount"]),
            -int(row["parentCount"]),
            str(row["name"]),
        )
    )
    return scattered


def locker_one_candidates(items: list[FlatItem]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for item in items:
        if item.locker != 1 or item.depth != 0:
            continue
        rows.append(
            {
                "path": item.path,
                "slot": item.slot,
                "name": item.name,
                "itemId": item.item_id,
                "category": classify_name(item.name, item.item_id),
                "isContainer": item.is_container,
            }
        )
    rows.sort(key=lambda row: int(row["slot"]))
    return rows


def category_summary(items: list[FlatItem], chains: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    item_counts: dict[str, int] = Counter()
    quantity_counts: dict[str, int] = Counter()

    for item in items:
        if item.is_container:
            continue
        category = classify_name(item.name, item.item_id)
        item_counts[category] += 1
        quantity_counts[category] += item.count

    summary: dict[str, Any] = {}
    for category in CATEGORY_LABELS:
        homes = chains.get(category, [])
        summary[category] = {
            "label": CATEGORY_LABELS[category],
            "itemEntries": item_counts.get(category, 0),
            "itemQuantity": quantity_counts.get(category, 0),
            "homeContainers": len(homes),
            "homeFreeSlotsEstimate": sum(int(home["freeSlotsEstimate"]) for home in homes),
        }
    return summary


def analyze(export: dict[str, Any]) -> dict[str, Any]:
    items = flatten_export(export)
    containers = [item for item in items if item.is_container]
    roles = infer_container_roles(items)
    inferred_chains = build_inferred_home_chains(roles)
    chains = build_managed_home_chains(roles)

    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sourceGeneratedAt": export.get("generatedAt"),
        "sourceSummary": export.get("summary", {}),
        "summary": {
            "flatItems": len(items),
            "containers": len(containers),
            "managedContainers": sum(1 for role in roles.values() if role["isManagedContainer"]),
            "protectedNestedItems": protected_nested_item_count(items, roles),
            "lockerOneTopLevelItems": len(locker_one_candidates(items)),
            "homeChains": {role: len(chain) for role, chain in sorted(chains.items())},
        },
        "categories": category_summary(items, chains),
        "homeChains": {
            role: [
                {
                    "path": item["path"],
                    "locker": item["locker"],
                    "slot": item["slot"],
                    "name": item["name"],
                    "source": item["source"],
                    "confidence": item["confidence"],
                    "directChildCount": item["directChildCount"],
                    "freeSlotsEstimate": item["freeSlotsEstimate"],
                    "isManagedContainer": item["isManagedContainer"],
                    "themeHint": item.get("themeHint"),
                }
                for item in chain
            ]
            for role, chain in sorted(chains.items())
        },
        "renameCandidates": rename_candidates(inferred_chains),
        "scatteredItems": scattered_items(items),
        "lockerOneCandidates": locker_one_candidates(items),
    }


def write_markdown(report: dict[str, Any], path: Path) -> None:
    lines: list[str] = []
    lines.append("# Locker Organizer Analysis")
    lines.append("")
    lines.append(f"- Generated at: `{report['generatedAt']}`")
    lines.append(f"- Source snapshot: `{report.get('sourceGeneratedAt')}`")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    for key, value in report["summary"].items():
        lines.append(f"- `{key}`: `{value}`")
    lines.append("")

    lines.append("## Categories")
    lines.append("")
    lines.append("| Category | Item entries | Quantity | Managed home containers | Managed free slots estimate |")
    lines.append("|---|---:|---:|---:|---:|")
    for category, row in report["categories"].items():
        lines.append(
            f"| {row['label']} | {row['itemEntries']} | {row['itemQuantity']} | "
            f"{row['homeContainers']} | {row['homeFreeSlotsEstimate']} |"
        )
    lines.append("")

    lines.append("## Rename Candidates")
    lines.append("")
    lines.append("| Path | Current | Suggested | Role | Confidence | Items |")
    lines.append("|---|---|---|---|---:|---:|")
    for row in report["renameCandidates"][:60]:
        lines.append(
            f"| `{row['path']}` | {row['currentName']} | {row['suggestedName']} | "
            f"{row['role']} | {row['confidence']} | {row['directChildCount']} |"
        )
    lines.append("")

    lines.append("## Scattered Item Groups")
    lines.append("")
    lines.append("Read-only diagnostic. Items inside unmanaged containers are protected and should not be moved by the planner.")
    lines.append("")
    lines.append("| Item | Category | Entries | Lockers | Parents | Example |")
    lines.append("|---|---|---:|---:|---:|---|")
    for row in report["scatteredItems"][:60]:
        example = row["examples"][0]["location"].replace("|", "/")
        lines.append(
            f"| {row['name']} | {row['category']} | {row['entries']} | "
            f"{row['lockerCount']} | {row['parentCount']} | {example} |"
        )
    lines.append("")

    lines.append("## Locker #1 Clearance Candidates")
    lines.append("")
    lines.append("| Slot | Item | Category | Container |")
    lines.append("|---:|---|---|---|")
    for row in report["lockerOneCandidates"]:
        lines.append(
            f"| {row['slot']} | {row['name']} | {row['category']} | "
            f"{'yes' if row['isContainer'] else 'no'} |"
        )
    lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export_json", type=Path, help="Path to locker-export-*.json")
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("build/locker-organizer"),
        help="Directory for analysis JSON and Markdown reports",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    export_path = args.export_json
    export = json.loads(export_path.read_text(encoding="utf-8"))
    report = analyze(export)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    stem = export_path.stem.replace("locker-export-", "")
    json_path = args.out_dir / f"locker-analysis-{stem}.json"
    md_path = args.out_dir / f"locker-analysis-{stem}.md"

    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    write_markdown(report, md_path)

    print(f"Wrote {json_path}")
    print(f"Wrote {md_path}")
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
