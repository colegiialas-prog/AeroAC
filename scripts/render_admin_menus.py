"""Render Bukkit admin-menu fixtures as a self-contained, reviewable HTML atlas.

The source JSON is produced by AdminMenusTest from the real menu renderers. This tool does not
invent metrics or pretend to be a Minecraft screenshot; it exposes every slot, item and lore line
for review without a running server.
"""

from __future__ import annotations

import argparse
import html
import json
from pathlib import Path


COLOURS = {
    "0": "#000000", "1": "#0000aa", "2": "#00aa00", "3": "#00aaaa",
    "4": "#aa0000", "5": "#aa00aa", "6": "#ffaa00", "7": "#aaaaaa",
    "8": "#555555", "9": "#5555ff", "a": "#55ff55", "b": "#55ffff",
    "c": "#ff5555", "d": "#ff55ff", "e": "#ffff55", "f": "#ffffff",
}


def minecraft_text(value: str) -> str:
    """Convert legacy colour/style codes to bounded HTML spans."""
    value = value or ""
    pieces: list[str] = []
    colour = "#ffffff"
    bold = False
    buffer: list[str] = []

    def flush() -> None:
        if not buffer:
            return
        weight = "font-weight:700;" if bold else ""
        pieces.append(f'<span style="color:{colour};{weight}">{html.escape("".join(buffer))}</span>')
        buffer.clear()

    index = 0
    while index < len(value):
        if value[index] == "§" and index + 1 < len(value):
            flush()
            code = value[index + 1].lower()
            if code in COLOURS:
                colour = COLOURS[code]
                bold = False
            elif code == "l":
                bold = True
            elif code == "r":
                colour, bold = "#ffffff", False
            index += 2
            continue
        buffer.append(value[index])
        index += 1
    flush()
    return "".join(pieces) or "&nbsp;"


def render(source: Path, destination: Path) -> None:
    payload = json.loads(source.read_text(encoding="utf-8"))
    screens = payload.get("screens")
    if not isinstance(screens, list) or not screens:
        raise ValueError("fixture contains no screens")

    normalised = []
    for number, screen in enumerate(screens):
        items = screen.get("items") or []
        if len(items) % 9:
            raise ValueError(f"screen {number} does not contain complete inventory rows")
        normalised.append({
            "permission": screen.get("permission") or "",
            "titleText": (screen.get("title") or "").replace("§b", "").replace("§l", ""),
            "titleHtml": minecraft_text(screen.get("title") or ""),
            "rows": len(items) // 9,
            "items": [{
                "material": item.get("material") or "AIR",
                "nameText": _plain(item.get("name") or ""),
                "nameHtml": minecraft_text(item.get("name") or ""),
                "loreHtml": [minecraft_text(line) for line in item.get("lore") or []],
            } for item in items],
        })

    encoded = json.dumps(normalised, ensure_ascii=False).replace("</", "<\\/")
    notice = html.escape(payload.get("notice") or "Renderer fixtures only.")
    document = TEMPLATE.replace("__SCREENS__", encoded).replace("__NOTICE__", notice)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(document, encoding="utf-8")


def _plain(value: str) -> str:
    output: list[str] = []
    index = 0
    while index < len(value):
        if value[index] == "§" and index + 1 < len(value):
            index += 2
        else:
            output.append(value[index])
            index += 1
    return "".join(output)


TEMPLATE = r'''<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Aero AC — atlas of Bukkit admin menus</title>
<style>
:root{color-scheme:dark;--bg:#080d11;--panel:#111a20;--line:#263842;--cyan:#55ffff;--muted:#93a5ad}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 20% 0,#15313a 0,#080d11 34rem);color:#eef7fa;font:14px/1.45 system-ui,sans-serif}
header{position:sticky;top:0;z-index:3;display:flex;gap:18px;align-items:end;flex-wrap:wrap;padding:18px 24px;background:#080d11e8;border-bottom:1px solid var(--line);backdrop-filter:blur(10px)}
h1{font-size:20px;margin:0;color:var(--cyan)}label{display:grid;gap:4px;color:var(--muted);font-size:12px}select{min-width:min(520px,85vw);padding:9px 11px;background:#101a20;color:#fff;border:1px solid #35515e;border-radius:5px}
main{display:grid;grid-template-columns:minmax(580px,1fr) minmax(280px,380px);gap:22px;padding:24px;max-width:1320px;margin:auto}.inventory-shell{background:#c6c6c6;border:4px solid;border-color:#fff #555 #555 #fff;padding:12px;box-shadow:0 20px 60px #000a}.title{min-height:27px;color:#303030;padding:2px 3px 8px;font:18px monospace}.grid{display:grid;grid-template-columns:repeat(9,minmax(52px,1fr));gap:3px}.slot{aspect-ratio:1;background:linear-gradient(135deg,#8b8b8b,#6f6f6f);border:3px solid;border-color:#373737 #eee #eee #373737;display:grid;place-items:center;position:relative;padding:3px;cursor:pointer;color:#fff;text-shadow:1px 1px #000;overflow:hidden}.slot:hover,.slot:focus{outline:3px solid #ffff55;z-index:1}.slot.empty{opacity:.48}.glyph{font:bold 11px/1.05 monospace;text-align:center;overflow-wrap:anywhere}.slot-no{position:absolute;right:2px;bottom:0;color:#ddd;font:9px monospace;text-shadow:1px 1px #000}.details{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:18px;min-height:260px}.details h2{font-size:18px;margin:0 0 5px;color:var(--cyan)}.details code{color:#b8cad1}.details ul{padding-left:19px}.meta{color:var(--muted);margin-bottom:18px}.notice{margin-top:20px;color:#9fb0b7;border-top:1px solid var(--line);padding-top:14px}.legend{margin:0 0 15px;color:#52636a;font-size:12px}@media(max-width:900px){main{grid-template-columns:1fr;padding:12px}.grid{grid-template-columns:repeat(9,minmax(38px,1fr))}.glyph{font-size:9px}}
</style></head>
<body><header><h1>Aero AC · Bukkit GUI atlas</h1><label>Экран<select id="screen"></select></label><div id="counter" class="meta"></div></header>
<main><section><div class="legend">Фактические renderer fixtures: нажмите слот, чтобы увидеть material, имя и lore.</div><div class="inventory-shell"><div id="title" class="title"></div><div id="grid" class="grid"></div></div></section>
<aside id="details" class="details"><h2>Выберите слот</h2><div class="meta">Содержимое будет показано здесь.</div><div class="notice">__NOTICE__</div></aside></main>
<script>const screens=__SCREENS__;const select=document.querySelector('#screen'),grid=document.querySelector('#grid'),title=document.querySelector('#title'),details=document.querySelector('#details'),counter=document.querySelector('#counter');
const short=m=>m==='BLACK_STAINED_GLASS_PANE'?'':m.split('_').filter(x=>!['STAINED','GLASS','PANE'].includes(x)).map(x=>x[0]).join('').slice(0,5);
screens.forEach((s,i)=>{const o=document.createElement('option');o.value=i;o.textContent=`${String(i+1).padStart(2,'0')} · ${s.titleText}`;select.append(o)});
function show(index){const s=screens[index];title.innerHTML=s.titleHtml;counter.textContent=`${index+1}/${screens.length} · ${s.rows} rows · permission ${s.permission}`;grid.replaceChildren();s.items.forEach((item,slot)=>{const b=document.createElement('button');b.className='slot '+(item.material==='BLACK_STAINED_GLASS_PANE'&&!item.nameText.trim()?'empty':'');b.innerHTML=`<span class="glyph">${short(item.material)}</span><span class="slot-no">${slot}</span>`;b.title=item.nameText||item.material;b.onclick=()=>inspect(item,slot,s);grid.append(b)});inspect(s.items.find(x=>x.nameText.trim())||s.items[0],s.items.findIndex(x=>x.nameText.trim()),s)}
function inspect(item,slot,s){details.innerHTML=`<h2>${item.nameHtml}</h2><div class="meta">slot ${slot} · <code>${item.material}</code><br>permission <code>${s.permission}</code></div>${item.loreHtml.length?`<ul>${item.loreHtml.map(x=>`<li>${x}</li>`).join('')}</ul>`:'<p class="meta">Lore отсутствует.</p>'}<div class="notice">__NOTICE__</div>`}
select.onchange=()=>show(Number(select.value));show(0);</script></body></html>'''


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path,
                        default=Path("bukkit/build/reports/admin-menus.json"))
    parser.add_argument("destination", nargs="?", type=Path,
                        default=Path("docs/admin-menu-preview.html"))
    arguments = parser.parse_args()
    render(arguments.source, arguments.destination)
    print(f"Rendered {arguments.destination}")


if __name__ == "__main__":
    main()
