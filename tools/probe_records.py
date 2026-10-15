"""
Read-only probe of Petlibro's cloud work records, run inside the Home Assistant container.

It reuses the session token the PETLIBRO integration saved if there is one; otherwise it signs in with the
account stored in the integration (Home Assistant then signs itself back in at its next refresh). It asks for each device's recent records with a few type filters, and prints only their shape: types,
field names and one sample per type. The token itself is never printed.

    docker exec homeassistant python3 /config/liberpet_probe.py
"""
import hashlib
import json
import time
import urllib.request

BASE = "https://api.us.petlibro.com"
entries = json.load(open("/config/.storage/core.config_entries"))["data"]["entries"]
entry = next(e for e in entries if e["domain"] == "petlibro")
tz = json.load(open("/config/.storage/core.config"))["data"].get("time_zone", "UTC")
HEADERS = {"source": "ANDROID", "language": "EN", "timezone": tz, "version": "1.3.45", "Content-Type": "application/json"}


def post(path, body):
    req = urllib.request.Request(BASE + path, json.dumps(body).encode(), HEADERS)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def short(v):
    s = json.dumps(v, ensure_ascii=False)
    return s if len(s) <= 90 else s[:87] + "..."


def records(data):
    """Flatten the day-grouped list ([{workRecords: [...]}, ...]) or a page ({records|list: [...]})."""
    if isinstance(data, dict):
        data = data.get("records") or data.get("list") or data.get("data") or []
    out = []
    for item in data or []:
        if isinstance(item, dict) and "workRecords" in item:
            out += item["workRecords"] or []
        else:
            out.append(item)
    return out


conf = {**entry.get("data", {}), **entry.get("options", {})}


def login():
    """Same login the integration does (MD5 of the password, the app's fixed id)."""
    r = post("/member/auth/login", {
        "appId": 1, "appSn": "c35772530d1041699c87fe62348507a8", "country": conf.get("region", "US"),
        "email": conf["email"], "password": hashlib.md5(conf["password"].encode()).hexdigest(),
        "phoneBrand": "", "phoneSystemVersion": "", "timezone": tz, "thirdId": None, "type": None,
    })
    if r.get("code") != 0:
        raise SystemExit(f"login failed: {r.get('code')} {r.get('msg')}")
    HEADERS["token"] = r["data"]["token"]
    print("(signed in fresh; Home Assistant will sign itself back in at its next refresh)")


if conf.get("token"):
    HEADERS["token"] = conf["token"]
listing = post("/device/device/list", {}) if "token" in HEADERS else {}
if listing.get("code") != 0:  # no saved token, or it expired
    login()
    listing = post("/device/device/list", {})
if listing.get("code") != 0:
    raise SystemExit(f"device list failed: {listing.get('code')} {listing.get('msg')}")
devices = listing["data"]

now = int(time.time() * 1000)
start = now - 3 * 86400 * 1000
for d in devices:
    sn, model = d["deviceSn"], d.get("productIdentifier")
    print(f"\n=== {model} …{sn[-4:]}")
    probes = [
        ("list, no type", "/device/workRecord/list", {"deviceSn": sn, "startTime": start, "endTime": now, "size": 50}),
        ("list, type []", "/device/workRecord/list", {"deviceSn": sn, "startTime": start, "endTime": now, "size": 50, "type": []}),
    ] + [
        (f"list, type {t}", "/device/workRecord/list", {"deviceSn": sn, "startTime": start, "endTime": now, "size": 50, "type": [t]})
        for t in ("DRINKING", "DEVICE_DRINKING", "DRINK_EVENT_NOTICE", "GRAIN_OUTPUT_SUCCESS")
    ] + [
        ("pageList", "/device/workRecord/pageList", {"deviceSn": sn, "startTime": start, "endTime": now, "pageIndex": 1, "pageSize": 50}),
    ]
    for name, path, body in probes:
        try:
            r = post(path, body)
        except Exception as e:  # network or HTTP error: show and move on
            print(f"  {name}: ERROR {e}")
            continue
        recs = records(r.get("data"))
        types = sorted({str(x.get("type")) for x in recs if isinstance(x, dict)})
        print(f"  {name}: code={r.get('code')} msg={r.get('msg')} records={len(recs)} types={types}")
        seen = set()
        for x in recs:
            if isinstance(x, dict) and x.get("type") not in seen:
                seen.add(x.get("type"))
                print("    sample:", {k: short(v) for k, v in x.items()})
