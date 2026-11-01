"""Runtime settings / API keys, editable from the web Settings panel.

Stored in backend/settings.json (git-ignored, chmod 600) and applied to os.environ so every
agent picks them up immediately - no restart. Values from a real environment / .env win on first
load only if settings.json has no value for that key.
"""
from __future__ import annotations

import json
import os
from typing import Optional

PATH = os.getenv("RELIEFSYNC_SETTINGS", "/tmp/reliefsync-settings.json" if os.getenv("VERCEL") else
                 os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "settings.json"))

# key -> (label, group, secret?, default, help)
FIELDS: dict[str, tuple] = {
    "ANTHROPIC_API_KEY": ("Anthropic API key", "AI", True, "",
                          "Claude structured triage. Without it a deterministic rule engine is used."),
    "RELIEFSYNC_MODEL": ("Claude model", "AI", False, "claude-haiku-5-5", "Fast model for triage."),
    "OPENAI_API_KEY": ("OpenAI API key", "AI", True, "",
                       "Whisper transcription + translation of recorded voice notes."),
    "TWILIO_ACCOUNT_SID": ("Twilio Account SID", "SMS", True, "", "Real SMS dispatch. Without it SMS is simulated."),
    "TWILIO_AUTH_TOKEN": ("Twilio Auth Token", "SMS", True, "", ""),
    "TWILIO_FROM": ("Twilio phone number", "SMS", False, "", "E.164, e.g. +14155550123"),
    "DEMO_PHONE_FOR": ("Stage volunteer (seed phone)", "SMS", False, "+15555550101",
                       "Seed volunteer whose texts get redirected (Maya Chen)."),
    "DEMO_PHONE_OVERRIDE": ("Redirect to real phone", "SMS", False, "",
                            "Your second demo phone; it receives Maya's dispatch SMS."),
    "MAP_PROVIDER": ("Map provider", "Maps", False, "osm",
                     "osm = OpenStreetMap (no key). maptiler, mapbox, stadia need an API key. custom = your own tile URL."),
    "MAP_API_KEY": ("Map API key", "Maps", True, "",
                    "MapTiler, Mapbox or Stadia key. Sent to browsers to load tiles (tiles are public by nature)."),
    "MAP_TILE_URL": ("Custom tile URL", "Maps", False, "",
                     "Only for provider = custom. Use {z}/{x}/{y} and {key}, e.g. https://tiles.example.com/{z}/{x}/{y}.png?key={key}"),
    "PUBLIC_URL": ("Public URL", "Server", False, "http://localhost:8077",
                   "Used in the navigation link texted to volunteers (e.g. your ngrok URL)."),
    "GEOCODE_ONLINE": ("Online geocoding (OSM Nominatim)", "Server", False, "0", "1 = on, 0 = offline gazetteer only."),
    "DISPATCH_TIMEOUT_S": ("Volunteer reply timeout (s)", "Server", False, "180", "Escalate to next volunteer after this."),
    "VOLUNTEER_RADIUS_MI": ("Volunteer search radius (mi)", "Server", False, "2.0", ""),
    "DEMO_TRAVEL_SECONDS": ("En-route animation length (s)", "Server", False, "45", ""),
}


def _read() -> dict:
    try:
        with open(PATH) as f:
            return {k: v for k, v in json.load(f).items() if k in FIELDS}
    except Exception:
        return {}


def load() -> None:
    """Apply stored settings to the environment (called at startup)."""
    for k, v in _read().items():
        if v not in (None, ""):
            os.environ[k] = str(v)


def get(key: str) -> str:
    return os.getenv(key) or FIELDS[key][3]


def num(key: str) -> float:
    try:
        return float(get(key))
    except ValueError:
        return float(FIELDS[key][3])


def mask(v: str) -> str:
    return "" if not v else ("•" * 6 + v[-4:] if len(v) > 8 else "•" * len(v))


def public() -> dict:
    out = []
    for k, (label, group, secret, default, help_) in FIELDS.items():
        v = os.getenv(k, "")
        out.append({"key": k, "label": label, "group": group, "secret": secret, "help": help_,
                    "default": default, "set": bool(v), "value": mask(v) if secret else (v or default)})
    return {"fields": out, "status": status()}


def status() -> dict:  # noqa: D401
    return {
        "mode": "live" if any(os.getenv(k) for k in ("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "TWILIO_ACCOUNT_SID")) else "demo",
        "triage": "claude" if os.getenv("ANTHROPIC_API_KEY") else "rules (simulated)",
        "transcription": "whisper" if os.getenv("OPENAI_API_KEY") else "on-device / browser speech",
        "sms": "twilio" if all(os.getenv(k) for k in ("TWILIO_ACCOUNT_SID", "TWILIO_AUTH_TOKEN", "TWILIO_FROM")) else "simulated",
        "map": settings_map_label(),
        "geocode": "nominatim + gazetteer" if os.getenv("GEOCODE_ONLINE") == "1" else "offline gazetteer",
    }


def update(values: dict[str, Optional[str]]) -> dict:
    """Save. Empty string clears a key; a masked value (unchanged secret) is ignored."""
    stored = _read()
    for k, v in values.items():
        if k not in FIELDS or v is None:
            continue
        v = str(v).strip()
        if FIELDS[k][2] and v.startswith("••"):
            continue
        if v == "":
            stored.pop(k, None)
            os.environ.pop(k, None)
        else:
            stored[k] = v
            os.environ[k] = v
    tmp = PATH + ".tmp"
    with open(tmp, "w") as f:
        json.dump(stored, f, indent=2)
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        pass
    os.replace(tmp, PATH)
    return public()


MAP_PROVIDERS = {
    # name: (url template, attribution, subdomains, needs_key)
    "osm": ("https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>', "", False),
    "maptiler": ("https://api.maptiler.com/maps/dataviz-dark/{z}/{x}/{y}.png?key={key}",
                 '&copy; <a href="https://www.maptiler.com/copyright/">MapTiler</a> &copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>', "", True),
    "mapbox": ("https://api.mapbox.com/styles/v1/mapbox/dark-v11/tiles/256/{z}/{x}/{y}@2x?access_token={key}",
               '&copy; <a href="https://www.mapbox.com/about/maps/">Mapbox</a> &copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>', "", True),
    "stadia": ("https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/{z}/{x}/{y}{r}.png?api_key={key}",
               '&copy; <a href="https://stadiamaps.com/">Stadia</a> &copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>', "", True),
}


def map_config() -> dict:
    prov = get("MAP_PROVIDER").strip().lower()
    if prov.startswith("carto"):  # CARTO removed -> OpenStreetMap
        prov = "osm"
    key = os.getenv("MAP_API_KEY", "")
    if prov == "custom" and os.getenv("MAP_TILE_URL"):
        url, attr, subs, needs = os.getenv("MAP_TILE_URL"), "Map tiles &copy; provider, data &copy; OpenStreetMap", "abc", "{key}" in os.getenv("MAP_TILE_URL")
    else:
        url, attr, subs, needs = MAP_PROVIDERS.get(prov, MAP_PROVIDERS["osm"])
        if prov not in MAP_PROVIDERS:
            prov = "osm"
    if needs and not key:  # key-required provider but no key -> safe keyless fallback
        prov, (url, attr, subs, needs) = "osm", MAP_PROVIDERS["osm"]
    return {"provider": prov, "url": url.replace("{key}", key), "attribution": attr, "subdomains": subs,
            "dark": prov != "osm"}


def settings_map_label() -> str:
    c = map_config()
    return c["provider"] + (" (key)" if os.getenv("MAP_API_KEY") and c["provider"] != "osm" else "")
