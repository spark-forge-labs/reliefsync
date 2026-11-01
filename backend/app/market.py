"""ReliefSync Marketplace – autonomous multi-agent negotiation and award ("Uber-style" dispatch).

Every volunteer is represented by a *volunteer agent* that holds the volunteer's service record
(rating, on-time rate, acceptance/cancellation history, certifications, battery, vehicle, price
floor, negotiation style).  The *dispatcher agent* broadcasts a Request-For-Quote (RFQ) signal and
the volunteer agents answer with bids; the dispatcher then negotiates price, scores every bid on a
weighted multi-factor model and awards the job.

Protocol (all messages are traced and streamed live to the Negotiation Room):

    RFQ  ──►  BID | DECLINE            every agent answers (or says why it cannot)
    COUNTER ──► ACCEPT | COUNTER | HOLD  bounded rounds; rounds depend on urgency
    AWARD ──► winner ; LOSE ──► others   with score feedback ("you were 3 min slower")

Adaptive urgency policy (life first, money second):
    P0  no haggling – instant award inside a short "golden window" to the best composite score;
        bids over the relief-fund cap are clamped to the cap, never argued.
    P1  one counter-offer round, award inside a wider window.
    P2  two rounds incl. best-and-final, full auction on value.

All randomness is seeded from (incident, volunteer) so the same incident always replays the same
negotiation – required for the serverless replay and for tests.
"""
from __future__ import annotations

import random
import zlib

# --------------------------------------------------------------------------- #
# Volunteer service records (what a real deployment would pull from the ledger)
# --------------------------------------------------------------------------- #
PROFILES: dict[str, dict] = {
    "Maya Chen": {"rating": 4.96, "jobs": 212, "accept": 0.93, "on_time": 0.97, "cancel": 0.01, "resp_s": 1.1,
                  "base": 6.0, "per_mile": 2.4, "floor": 9.0, "style": "firm", "battery": 86,
                  "certs": ["EMT-B", "CPR", "Power-equipment"], "capacity": "large", "pro_bono": False,
                  "reviews": ["Calm under pressure, got the oxygen unit running.", "Early again. 5★"]},
    "Diego Ramirez": {"rating": 4.71, "jobs": 148, "accept": 0.88, "on_time": 0.91, "cancel": 0.03, "resp_s": 1.8,
                      "base": 5.0, "per_mile": 2.0, "floor": 7.0, "style": "fair", "battery": 74,
                      "certs": ["Boat-licence", "Swift-water-rescue"], "capacity": "large", "pro_bono": False,
                      "reviews": ["Knew the flooded back streets.", "A bit late but kind."]},
    "Priya Natarajan": {"rating": 4.88, "jobs": 176, "accept": 0.95, "on_time": 0.94, "cancel": 0.02, "resp_s": 1.4,
                        "base": 4.0, "per_mile": 1.8, "floor": 5.0, "style": "generous", "battery": 63,
                        "certs": ["Paediatric-first-aid", "Food-handler"], "capacity": "medium", "pro_bono": False,
                        "reviews": ["Great with the baby formula run.", "Friendly, texted updates."]},
    "Sam Okafor": {"rating": 4.52, "jobs": 61, "accept": 0.79, "on_time": 0.86, "cancel": 0.07, "resp_s": 2.6,
                   "base": 2.0, "per_mile": 1.2, "floor": 3.0, "style": "aggressive", "battery": 41,
                   "certs": ["Food-handler"], "capacity": "small", "pro_bono": False,
                   "reviews": ["Fast on the bike, small loads only.", "Cancelled once (flat tyre)."]},
    "Lena Novak": {"rating": 4.99, "jobs": 305, "accept": 0.97, "on_time": 0.98, "cancel": 0.00, "resp_s": 0.9,
                   "base": 0.0, "per_mile": 0.0, "floor": 0.0, "style": "generous", "battery": 92,
                   "certs": ["Registered-nurse", "CPR", "AED"], "capacity": "small", "pro_bono": True,
                   "reviews": ["Volunteers for free, always first on scene.", "Nurse – stayed with Mum until help came."]},
}

STYLE = {  # how far above the walk-away floor the opening ask sits and how readily the agent concedes
    "firm": {"markup": 0.30, "concede": 0.30},
    "fair": {"markup": 0.22, "concede": 0.55},
    "generous": {"markup": 0.12, "concede": 0.80},
    "aggressive": {"markup": 0.45, "concede": 0.20},
}

# relief-fund cap (USD reimbursement) and the dispatcher's opening target by urgency
BUDGET = {"P0": {"cap": 30.0, "target": 20.0, "rounds": 0, "window_ms": 1500},
          "P1": {"cap": 24.0, "target": 11.0, "rounds": 1, "window_ms": 2600},
          "P2": {"cap": 16.0, "target": 7.0, "rounds": 2, "window_ms": 10 ** 9}}

WEIGHTS = {  # composite score weights (sum to 1)
    "P0": {"eta": .38, "quality": .16, "reliability": .14, "fit": .14, "speed": .10, "price": .04, "safety": .04},
    "P1": {"eta": .30, "quality": .18, "reliability": .14, "fit": .12, "price": .14, "speed": .06, "safety": .06},
    "P2": {"eta": .20, "price": .25, "quality": .20, "reliability": .14, "fit": .10, "speed": .04, "safety": .07},
}

CERT_FOR = {  # which credentials make an agent a better fit for each need category
    "MEDICAL": {"EMT-B", "Registered-nurse", "CPR", "AED", "Paediatric-first-aid"},
    "POWER": {"Power-equipment", "EMT-B"},
    "EVACUATION": {"Boat-licence", "Swift-water-rescue"},
    "INFANT_CARE": {"Paediatric-first-aid", "Food-handler"},
    "FOOD_WATER": {"Food-handler"},
}


def _rng(*parts) -> random.Random:
    return random.Random(zlib.crc32("|".join(str(p) for p in parts).encode()))


def profile_for(v: dict) -> dict:
    """Seeded profile by name; any other volunteer gets a stable synthetic record."""
    p = PROFILES.get(v["name"])
    if p:
        return dict(p)
    r = _rng("profile", v["id"])
    return {"rating": round(4.1 + r.random() * 0.85, 2), "jobs": r.randint(5, 150), "accept": round(0.7 + r.random() * 0.28, 2),
            "on_time": round(0.8 + r.random() * 0.18, 2), "cancel": round(r.random() * 0.08, 2), "resp_s": round(0.9 + r.random() * 2.2, 1),
            "base": round(2 + r.random() * 4, 1), "per_mile": round(1 + r.random() * 1.6, 2), "floor": round(3 + r.random() * 5, 1),
            "style": r.choice(list(STYLE)), "battery": r.randint(30, 95), "certs": [], "capacity": "medium",
            "pro_bono": False, "reviews": []}


def money(x: float) -> str:
    return "pro-bono" if x <= 0 else f"${x:.2f}"


def surge_multiplier(open_incidents: int, idle_agents: int) -> float:
    """Demand/supply: many open incidents per idle agent raises asks (capped at 1.5×)."""
    ratio = open_incidents / max(1, idle_agents)
    return round(min(1.5, 1.0 + max(0.0, ratio - 0.5) * 0.35), 2)


def _fit(cat: str, p: dict, transport_fit: float, skill: bool) -> float:
    certs = len(CERT_FOR.get(cat, set()) & set(p["certs"]))
    return round(min(1.0, 0.55 * transport_fit + 0.25 * (1.0 if skill else 0.0) + 0.20 * min(1.0, certs / 2)), 3)


# --------------------------------------------------------------------------- #
# Volunteer-agent side: decide whether / how to bid
# --------------------------------------------------------------------------- #
def evaluate_rfq(c: dict, p: dict, rfq: dict) -> dict:
    """Return {"bid": bool, ...}. `c` is the ranked candidate (carries road trip time etc.)."""
    if p["battery"] < 15:
        return {"bid": False, "reason": f"device battery {p['battery']}% – cannot stay online"}
    if rfq["category"] == "EVACUATION" and c["transport"] in ("FOOT", "BICYCLE"):
        return {"bid": False, "reason": f"{c['transport'].lower()} cannot carry an evacuation"}
    if c["trip_min"] > rfq["max_eta_min"]:
        return {"bid": False, "reason": f"ETA {c['trip_min']:.0f} min exceeds the {rfq['max_eta_min']:.0f} min limit"}
    miles = c["trip_miles"]
    pickup_fee = 1.5 if c.get("resource") else 0.0
    risk = 1.0 + (0.12 * len(rfq.get("blocked", [])))          # detour through hazard zones
    cost = (p["base"] + p["per_mile"] * miles + 0.08 * c["trip_min"] + pickup_fee) * rfq["surge"] * risk
    floor = 0.0 if p["pro_bono"] else max(p["floor"], cost * 0.85)
    ask = 0.0 if p["pro_bono"] else round(max(floor, cost * (1 + STYLE[p["style"]]["markup"])), 2)
    r = _rng("latency", rfq["iid"], c["id"])
    latency_ms = int(p["resp_s"] * 1000 * (0.75 + r.random() * 0.5))
    return {"bid": True, "ask": ask, "floor": round(floor, 2), "latency_ms": latency_ms,
            "eta_min": round(c["trip_min"], 1), "miles": c["trip_miles"], "surge": rfq["surge"]}


def respond_counter(p: dict, bid: dict, offer: float, final: bool) -> dict:
    """Volunteer agent answers the dispatcher's counter-offer."""
    if offer >= bid["ask"] - 1e-6:
        return {"kind": "ACCEPT", "price": bid["ask"], "text": "Accepted."}
    if offer >= bid["floor"] - 1e-6:
        # quality premium: top-rated agents decline to go much below their ask unless generous
        premium_hold = (p["rating"] - 4.5) * 0.35 * (1 - STYLE[p["style"]]["concede"])
        want = bid["ask"] - (bid["ask"] - bid["floor"]) * STYLE[p["style"]]["concede"] * (1 - premium_hold)
        if offer >= want - 1e-6 or final:
            return {"kind": "ACCEPT", "price": round(offer, 2), "text": f"Deal at {money(offer)}."}
        mid = round(max(bid["floor"], (want + offer) / 2), 2)
        return {"kind": "COUNTER", "price": mid,
                "text": f"{money(offer)} is tight for a {p['rating']}★ / {p['on_time']:.0%} on-time record – can do {money(mid)}."}
    if final:
        return {"kind": "DECLINE", "price": bid["floor"], "text": f"Below my floor ({money(bid['floor'])}); withdrawing."}
    return {"kind": "HOLD", "price": bid["floor"], "text": f"{money(offer)} is under my floor – best I can do is {money(bid['floor'])}."}


# --------------------------------------------------------------------------- #
# Dispatcher-agent side: scoring and award
# --------------------------------------------------------------------------- #
def score_bid(b: dict, urgency: str, cap: float, min_eta: float, min_resp: float, transport_fit: float,
              skill: bool, cat: str) -> dict:
    p = b["profile"]
    comps = {
        "eta": round(min_eta / max(b["eta_min"], 0.1), 3),
        "price": 1.0 if b["price"] <= 0 else round(max(0.0, 1 - b["price"] / cap), 3),
        "quality": round(min(1.0, 0.6 * max(0.0, (p["rating"] - 3.5) / 1.5) + 0.4 * p["on_time"]), 3),
        "reliability": round(max(0.0, min(1.0, p["accept"] * (1 - 2 * p["cancel"]))), 3),
        "fit": _fit(cat, p, transport_fit, skill),
        "speed": round(min(1.0, min_resp / max(b["latency_ms"], 1)), 3),
        "safety": round(max(0.0, min(1.0, 0.5 + p["battery"] / 200 - 0.08 * len(b.get("blocked", [])))), 3),
    }
    w = WEIGHTS[urgency]
    total = round(100 * sum(w[k] * comps[k] for k in w), 1)
    return {"components": comps, "weights": w, "score": total}
