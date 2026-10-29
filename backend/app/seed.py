"""Demo scenario seed data (spec section 7: 5 volunteers, 10 supplies, 1 flood hazard).

Everything is centred on the demo incident location (12th St near St. Jude's,
37.7749, -122.4194) and sits inside a ~3 mile radius.
"""

CENTER = (37.7749, -122.4194)

# Local gazetteer used by the geo resolver before falling back to Nominatim.
# keys are lowercase landmark phrases; the longest match wins.
GAZETTEER = {
    "st. jude's": (37.7749, -122.4194, "12th St near St. Jude's Church"),
    "st jude": (37.7749, -122.4194, "12th St near St. Jude's Church"),
    "12th street near st": (37.7749, -122.4194, "12th St near St. Jude's Church"),
    "12th street": (37.7752, -122.4188, "12th St"),
    "12th st": (37.7752, -122.4188, "12th St"),
    "shell station": (37.7712, -122.4251, "Shell station, 4th St"),
    "4th street": (37.7716, -122.4247, "4th St"),
    "lincoln high": (37.7838, -122.4105, "Lincoln High School shelter"),
    "riverside park": (37.7671, -122.4302, "Riverside Park"),
    "mercy hospital": (37.7861, -122.4290, "Mercy General Hospital"),
    "main avenue": (37.7790, -122.4160, "Main Ave"),
    "main ave": (37.7790, -122.4160, "Main Ave"),
    "harbor bridge": (37.7695, -122.4080, "Harbor Bridge"),
    "community center": (37.7808, -122.4232, "Eastside Community Center"),
}

VOLUNTEERS = [
    # name, phone, lat, lon, transport, skills
    ("Maya Chen", "+15555550101", 37.7822, -122.4178, "4WD", ["medical", "power"]),
    ("Diego Ramirez", "+15555550102", 37.7722, -122.4150, "BOAT", ["evacuation"]),
    ("Priya Natarajan", "+15555550103", 37.7812, -122.4098, "CAR", ["infant", "food"]),
    ("Sam Okafor", "+15555550104", 37.7690, -122.4240, "BICYCLE", ["food", "water"]),
    ("Lena Novak", "+15555550105", 37.7853, -122.4282, "FOOT", ["medical"]),
]

RESOURCES = [
    # provider, item, qty, lat, lon, tags (semantic hints)
    ("Lincoln High Shelter", "Portable Power Station 1000Wh", 3, 37.7838, -122.4105,
     "battery generator electricity power backup oxygen concentrator charge"),
    ("Lincoln High Shelter", "Bottled Water 24-pack", 40, 37.7838, -122.4105,
     "drinking water hydration thirst"),
    ("Eastside Community Center", "Enfamil Infant Formula, Stage 1", 18, 37.7808, -122.4232,
     "baby milk infant formula newborn feeding"),
    ("Eastside Community Center", "Portable Power Station 500Wh", 2, 37.7808, -122.4232,
     "battery generator electricity power backup oxygen concentrator charge"),
    ("Eastside Community Center", "Diapers Size 2", 30, 37.7808, -122.4232,
     "baby infant nappies diaper"),
    ("Mercy General Hospital", "Portable Oxygen Cylinder (E-tank)", 6, 37.7861, -122.4290,
     "oxygen breathing respiratory copd concentrator"),
    ("Mercy General Hospital", "Insulin Pens (Pediatric)", 12, 37.7861, -122.4290,
     "insulin diabetes diabetic pediatric medication"),
    ("Riverside Mutual Aid Pantry", "Ready-to-eat Meal Kits", 60, 37.7671, -122.4302,
     "food meals hungry ration groceries"),
    ("Riverside Mutual Aid Pantry", "First Aid Trauma Kit", 9, 37.7671, -122.4302,
     "first aid bandage bleeding wound injury medical"),
    ("Harbor Rescue Co-op", "Inflatable Rescue Raft", 2, 37.7695, -122.4080,
     "boat evacuation flood rescue stranded trapped"),
    ("Harbor Rescue Co-op", "Thermal Blankets", 50, 37.7695, -122.4080,
     "cold hypothermia warm blanket elderly"),
]

# Flooded stretch of "Main Ave" (Market St between 10th and 7th St): a ~600 m x 60 m strip
# that cuts across the direct path between the shelters and the caller.
HAZARDS = [
    {
        "id": "hz-main-ave-flood",
        "name": "Main Ave flooding (impassable)",
        "kind": "FLOOD",
        "polygon": [
            [37.776124, -122.417397],
            [37.779664, -122.412737],
            [37.780096, -122.413263],
            [37.776556, -122.417923],
        ],
    }
]
