#!/usr/bin/env python3
"""Static geometry checks for the responsive FCM Guard dashboard.

Android still performs the real resource/layout compilation in assembleDebug. These
checks guard the dimensions that are easiest to regress on compact HyperOS phones.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/res/values/dimens.xml"
LARGE = ROOT / "app/src/main/res/values-w420dp/dimens.xml"


def read_dp(path: Path):
    root = ET.parse(path).getroot()
    out = {}
    for item in root.findall("dimen"):
        raw = (item.text or "").strip()
        if raw.endswith("dp"):
            out[item.attrib["name"]] = float(raw[:-2])
    return out


base = read_dp(BASE)
large = {**base, **read_dp(LARGE)}

profiles = [
    ("narrow-320dp", 320),
    ("compact-360dp", 360),
    ("xiaomi17-class-393dp", 393),
    ("compact-411dp", 411),
    ("large-430dp", 430),
    ("ultra-class-480dp", 480),
]

for name, width in profiles:
    d = large if width >= 420 else base
    page = d["page_horizontal_padding"]
    hero = width - 2 * page
    inner = hero - 2 * d["hero_inner_padding"]
    title_space = inner - d["language_button_width"] - d["language_button_margin_start"]
    status = hero - 2 * d["status_card_inset"]

    assert hero >= 285, f"{name}: hero too narrow ({hero}dp)"
    assert title_space >= 135, f"{name}: title/language row too tight ({title_space}dp)"
    assert status >= 265, f"{name}: status card too narrow ({status}dp)"
    assert abs((28 - d["status_card_radius"]) - d["status_card_inset"]) < 0.01, (
        f"{name}: status and hero corner geometry is no longer concentric"
    )
    assert d["status_expanded_top"] < d["hero_height"], (
        f"{name}: floating status card no longer overlaps the hero"
    )

layout = ET.parse(ROOT / "app/src/main/res/layout/activity_main.xml").getroot()
android_id = "{http://schemas.android.com/apk/res/android}id"
android_width = "{http://schemas.android.com/apk/res/android}layout_width"
ids = {node.attrib.get(android_id) for node in layout.iter()}
for required in (
    "@+id/stickyHeader",
    "@+id/heroCard",
    "@+id/statusCard",
    "@+id/statusHeaderPanel",
    "@+id/statusDetailsPanel",
    "@+id/currentValuePanel",
    "@+id/scroll",
):
    assert required in ids, f"missing required dashboard id: {required}"

status_card = next(node for node in layout.iter() if node.attrib.get(android_id) == "@+id/statusCard")
assert status_card.tag == "com.reed.fcmguard.CollapsingStatusCard", (
    "statusCard must keep its custom visual-bottom implementation"
)

details_panel = next(node for node in layout.iter() if node.attrib.get(android_id) == "@+id/statusDetailsPanel")
value_panel = next(node for node in layout.iter() if node.attrib.get(android_id) == "@+id/currentValuePanel")
assert details_panel.attrib.get(android_width) == "match_parent", (
    "statusDetailsPanel must span the same card width as the whitelist panel"
)
assert value_panel.attrib.get(android_width) == "match_parent", (
    "currentValuePanel must span the same card width as the detailed status cover"
)

print("Responsive layout checks passed for:")
for name, width in profiles:
    print(f"  - {name}: {width}dp")
