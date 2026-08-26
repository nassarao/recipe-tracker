#!/usr/bin/env python3
"""Generate the bundled Recipe Tracker index from OSRS Wiki Bucket data.

The generated file is committed with the plugin and read locally at runtime.
Live Wiki lookups remain available as a fallback for recipes absent from this
snapshot.

The extraction approach is adapted from KludensVogter/Recipes_Checklist under
the BSD-2-Clause license. See THIRD_PARTY_NOTICES.md.
"""

import json
import re
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path


API = "https://oldschool.runescape.wiki/api.php"
USER_AGENT = "Recipe-Tracker-RuneLite-Plugin/1.0 (recipe index generator)"
PAGE_SIZE = 2000
OUT_PATH = (Path(__file__).resolve().parents[1]
            / "src/main/resources/com/recipetracker/recipes.json")

WIKI_LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]|]*)\]\]")
ALL_WIKI_LINKS = re.compile(r"\[\[(?:([^\]|#]+)(?:#[^\]|]*)?)(?:\|([^\]]+))?\]\]")
TRAILING_QUALIFIER = re.compile(r"\s+\([^()]*\)$")


def query(expression):
    url = API + "?" + urllib.parse.urlencode({
        "action": "bucket",
        "format": "json",
        "query": expression,
    })
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.load(response)
    if "error" in payload:
        raise RuntimeError("Bucket API error: %s" % payload["error"])
    return payload.get("bucket", [])


def fetch_all(bucket, fields):
    rows = []
    offset = 0
    while True:
        selected = ",".join("'%s'" % field for field in fields)
        expression = "bucket('%s').select(%s).limit(%d).offset(%d).run()" % (
            bucket, selected, PAGE_SIZE, offset)
        page = query(expression)
        rows.extend(page)
        if len(page) < PAGE_SIZE:
            return rows
        offset += PAGE_SIZE
        print("  ...%d rows" % offset, file=sys.stderr)


def clean(name):
    name = WIKI_LINK.sub(r"\1", str(name or ""))
    return name.split("#", 1)[0].strip()


def anchor_of(name):
    name = WIKI_LINK.sub(r"\1", str(name or ""))
    if "#" not in name:
        return ""
    return name.split("#", 1)[1].split("|", 1)[0].strip()


def resolve(name, items):
    candidate = name
    for _ in range(3):
        item_id = items.get(candidate.lower())
        if item_id:
            return item_id
        shorter = TRAILING_QUALIFIER.sub("", candidate)
        if shorter == candidate:
            return None
        candidate = shorter
    return None


def parse_quantity(raw):
    if isinstance(raw, (int, float)):
        return float(raw) if raw > 0 else None
    match = re.match(r"^(\d+(?:\.\d+)?)", str(raw or "").replace(",", "").strip())
    return float(match.group(1)) if match else None


def json_number(value):
    return int(value) if float(value).is_integer() else value


def first_item_id(raw):
    try:
        return int(raw[0] if isinstance(raw, list) else raw)
    except (TypeError, ValueError):
        return None


def build_item_index():
    print("Fetching item ids...", file=sys.stderr)
    index = {}
    names = {}
    for row in fetch_all("infobox_item", ["item_name", "item_id"]):
        name = row.get("item_name")
        item_id = first_item_id(row.get("item_id"))
        if not name or not item_id:
            continue
        name = name.strip()
        index.setdefault(name.lower(), item_id)
        names.setdefault(item_id, name)

    # Furniture and ship parts can be scenery rather than inventory items. These
    # buckets provide a matching flatpack/part id when one exists.
    for bucket in ("infobox_construction", "infobox_ship_part"):
        print("Fetching %s..." % bucket, file=sys.stderr)
        for row in fetch_all(bucket, ["page_name", "item_id"]):
            name = row.get("page_name")
            item_id = first_item_id(row.get("item_id"))
            if not name or not item_id:
                continue
            name = name.strip()
            index.setdefault(name.lower(), item_id)
            names.setdefault(item_id, name)
    return index, names


def build_watered_aliases(items, item_names):
    aliases = {}
    for watered_id, watered_name in item_names.items():
        if not watered_name.endswith(" (w)"):
            continue
        base_id = items.get(watered_name[:-len(" (w)")].lower())
        if base_id and base_id != watered_id:
            aliases[watered_id] = base_id
    return aliases


def parse_tools(raw, items, item_names, aliases):
    if not raw:
        return []
    text = " ".join(str(value) for value in raw) if isinstance(raw, list) else str(raw)
    links = ALL_WIKI_LINKS.findall(text)
    if links:
        candidates = [(label or target).strip() for target, label in links]
    else:
        text = re.sub(r"<br\s*/?>", ",", text, flags=re.IGNORECASE)
        candidates = re.split(r"\s*(?:,|;|\bor\b|\band\b)\s*", text)

    tools = []
    seen = set()
    for candidate in candidates:
        name = clean(candidate)
        item_id = resolve(name, items)
        if not item_id:
            continue
        item_id = aliases.get(item_id, item_id)
        if item_id in seen:
            continue
        seen.add(item_id)
        tools.append({
            "name": item_names.get(item_id, name),
            "itemId": item_id,
            "quantity": 1,
            "type": "tool",
        })
    return tools


def variant_names(product_name, variants, item_names):
    if len(variants) == 1:
        return [(product_name, variants[0])]

    shared = None
    for variant in variants:
        ids = {requirement["itemId"] for requirement in variant["materials"]}
        shared = ids if shared is None else shared & ids

    named = []
    for variant in variants:
        label = variant["variant"]
        if not label:
            distinct = [requirement["itemId"] for requirement in variant["materials"]
                        if requirement["itemId"] not in shared]
            if distinct:
                label = item_names.get(distinct[0], "")
        name = "%s (%s)" % (product_name, label) if label else product_name
        named.append((name, variant))
    return named


def build_recipes(items, item_names, aliases):
    print("Fetching recipes...", file=sys.stderr)
    rows = fetch_all("recipe", ["production_json"])
    print("  %d raw recipes" % len(rows), file=sys.stderr)

    seen = set()
    by_output = defaultdict(list)
    skipped = defaultdict(int)

    for row in rows:
        try:
            data = json.loads(row.get("production_json") or "{}")
        except ValueError:
            skipped["unparseable"] += 1
            continue

        output = data.get("output")
        materials = data.get("materials")
        if not isinstance(output, dict) or not materials:
            skipped["no output or materials"] += 1
            continue

        raw_name = clean(output.get("name"))
        if not raw_name:
            skipped["no product name"] += 1
            continue

        product_id = resolve(raw_name, items)
        if product_id:
            product_id = aliases.get(product_id, product_id)
            product_name = item_names[product_id]
        else:
            product_id = 0
            product_name = raw_name

        requirements = []
        for material in materials:
            if not isinstance(material, dict):
                break
            material_name = clean(material.get("name"))
            item_id = resolve(material_name, items)
            quantity = parse_quantity(material.get("quantity"))
            if not item_id or quantity is None:
                break

            canonical_id = aliases.get(item_id, item_id)
            requirement = {
                "name": item_names.get(canonical_id, material_name),
                "itemId": canonical_id,
                "quantity": json_number(quantity),
                "type": "material",
            }
            if canonical_id != item_id:
                requirement["alternativeItemIds"] = [item_id]
            requirements.append(requirement)
        else:
            if not requirements:
                continue
            if any(requirement["itemId"] == product_id for requirement in requirements):
                skipped["makes itself"] += 1
                continue

            output_quantity = parse_quantity(output.get("quantity")) or 1
            tools = parse_tools(data.get("tools"), items, item_names, aliases)
            skills = data.get("skills") or []
            category = clean(skills[0].get("name")) if skills and isinstance(skills[0], dict) else "Other"
            variant = (clean(output.get("subtxt"))
                       or anchor_of(output.get("name"))
                       or clean(data.get("facilities")))
            signature = (
                product_id,
                output_quantity,
                tuple(sorted((entry["itemId"], entry["quantity"]) for entry in requirements)),
                tuple(sorted(entry["itemId"] for entry in tools)),
            )
            if signature in seen:
                continue
            seen.add(signature)
            by_output[product_name].append({
                "productId": product_id,
                "outputQuantity": json_number(output_quantity),
                "materials": requirements,
                "tools": tools,
                "category": category or "Other",
                "variant": variant,
            })
            continue

        skipped["unknown material"] += 1

    recipes = []
    used = set()
    for product_name, variants in by_output.items():
        for proposed_name, variant in variant_names(product_name, variants, item_names):
            name = proposed_name
            suffix = 2
            while name.lower() in used:
                name = "%s (%d)" % (proposed_name, suffix)
                suffix += 1
            used.add(name.lower())

            recipes.append({
                "name": name,
                "category": variant["category"],
                "variant": variant["variant"],
                "outputItemId": variant["productId"],
                "outputQuantity": variant["outputQuantity"],
                "requirements": variant["materials"] + variant["tools"],
            })

    recipes.sort(key=lambda recipe: (recipe["category"].lower(), recipe["name"].lower()))
    for reason, count in sorted(skipped.items(), key=lambda entry: -entry[1]):
        print("  skipped %5d (%s)" % (count, reason), file=sys.stderr)
    return recipes


def main():
    items, item_names = build_item_index()
    print("  %d items indexed" % len(items), file=sys.stderr)
    aliases = build_watered_aliases(items, item_names)
    print("  %d watered items folded into base items" % len(aliases), file=sys.stderr)
    recipes = build_recipes(items, item_names, aliases)

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUT_PATH.open("w", encoding="utf-8", newline="\n") as output:
        json.dump({"schemaVersion": 1, "recipes": recipes}, output,
                  ensure_ascii=False, separators=(",", ":"))
        output.write("\n")
    print("Wrote %d recipes to %s" % (len(recipes), OUT_PATH), file=sys.stderr)


if __name__ == "__main__":
    main()
