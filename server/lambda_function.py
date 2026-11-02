"""PremDue licence server: Google sign-in, 7-day trial, ₹299 Razorpay purchase. No customer data is ever sent here."""
import base64, hashlib, hmac, json, os, time, urllib.request
import boto3

TABLE = boto3.resource("dynamodb").Table(os.environ["TABLE"])
WEB_CLIENT_ID = os.environ.get("GOOGLE_WEB_CLIENT_ID", "")
KEY_ID = os.environ.get("RAZORPAY_KEY_ID", "")
KEY_SECRET = os.environ.get("RAZORPAY_KEY_SECRET", "")
WEBHOOK_SECRET = os.environ.get("RAZORPAY_WEBHOOK_SECRET", "")
ORIGIN = os.environ.get("PAY_ORIGIN", "https://finscious.github.io")
PRICE_PAISE = 29900
TRIAL_SECONDS = 7 * 24 * 3600
_jwks = {"at": 0, "keys": {}}


def b64(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def google_keys():
    if time.time() - _jwks["at"] > 3600:
        with urllib.request.urlopen("https://www.googleapis.com/oauth2/v3/certs", timeout=5) as r:
            _jwks["keys"] = {k["kid"]: k for k in json.load(r)["keys"]}
        _jwks["at"] = time.time()
    return _jwks["keys"]


def verify_google(token):
    """Verifies a Google ID token (RS256) and returns its claims, or None."""
    try:
        h, p, s = token.split(".")
        head, claims = json.loads(b64(h)), json.loads(b64(p))
        key = google_keys().get(head.get("kid"))
        if head.get("alg") != "RS256" or key is None:
            return None
        n, e = int.from_bytes(b64(key["n"]), "big"), int.from_bytes(b64(key["e"]), "big")
        k = (n.bit_length() + 7) // 8
        em = pow(int.from_bytes(b64(s), "big"), e, n).to_bytes(k, "big")
        digest_info = bytes.fromhex("3031300d060960864801650304020105000420") + hashlib.sha256(f"{h}.{p}".encode()).digest()
        expected = b"\x00\x01" + b"\xff" * (k - len(digest_info) - 3) + b"\x00" + digest_info
        ok = (
            hmac.compare_digest(em, expected)
            and claims.get("iss") in ("accounts.google.com", "https://accounts.google.com")
            and claims.get("aud") == WEB_CLIENT_ID
            and claims.get("exp", 0) > time.time()
            and claims.get("email_verified") is True
        )
        return claims if ok else None
    except Exception:
        return None


def user_status(sub, email):
    """Returns the user's licence, starting the trial on first sign-in (server time, cannot be reset)."""
    now = int(time.time())
    TABLE.update_item(
        Key={"sub": sub},
        UpdateExpression="SET email = :e, trialStart = if_not_exists(trialStart, :n), paid = if_not_exists(paid, :f)",
        ExpressionAttributeValues={":e": email, ":n": now, ":f": False},
    )
    u = TABLE.get_item(Key={"sub": sub}, ConsistentRead=True)["Item"]
    return u, {"paid": bool(u["paid"]), "trialEnd": int(u["trialStart"]) + TRIAL_SECONDS, "now": now}


def razorpay(method, path, body=None):
    req = urllib.request.Request(
        "https://api.razorpay.com/v1" + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Basic " + base64.b64encode(f"{KEY_ID}:{KEY_SECRET}".encode()).decode(),
        },
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


def mark_paid(order):
    """Marks the user of a Razorpay order or payment link as paid once the full price is received."""
    sub = (order.get("notes") or {}).get("sub")
    if order.get("status") == "paid" and order.get("amount_paid") == PRICE_PAISE and sub:
        TABLE.update_item(
            Key={"sub": sub},
            UpdateExpression="SET paid = :t, paymentRef = :o, paidAt = if_not_exists(paidAt, :n)",
            ConditionExpression="attribute_exists(#s)",
            ExpressionAttributeNames={"#s": "sub"},
            ExpressionAttributeValues={":t": True, ":o": order["id"], ":n": int(time.time())},
        )
        return True
    return False


def reply(code, body):
    return {
        "statusCode": code,
        "headers": {"Content-Type": "application/json", "Access-Control-Allow-Origin": ORIGIN},
        "body": json.dumps(body),
    }


def lambda_handler(event, _):
    path = event.get("rawPath", "")
    raw = event.get("body") or ""
    if event.get("isBase64Encoded"):
        raw = base64.b64decode(raw).decode()

    if path == "/webhook":
        sig = (event.get("headers") or {}).get("x-razorpay-signature", "")
        good = hmac.new(WEBHOOK_SECRET.encode(), raw.encode(), hashlib.sha256).hexdigest()
        if not WEBHOOK_SECRET or not hmac.compare_digest(sig, good):
            return reply(401, {"error": "bad signature"})
        e = json.loads(raw)
        if e.get("event") == "order.paid":
            mark_paid(e["payload"]["order"]["entity"])
        elif e.get("event") == "payment_link.paid":
            mark_paid(e["payload"]["payment_link"]["entity"])
        return reply(200, {"ok": True})

    try:
        data = json.loads(raw or "{}")
    except ValueError:
        return reply(400, {"error": "bad json"})

    if path == "/verify":  # called by the pay page after checkout
        o, p, s = data.get("order_id", ""), data.get("payment_id", ""), data.get("signature", "")
        good = hmac.new(KEY_SECRET.encode(), f"{o}|{p}".encode(), hashlib.sha256).hexdigest()
        if not KEY_SECRET or not o.startswith("order_") or not hmac.compare_digest(s, good):
            return reply(401, {"error": "bad signature"})
        return reply(200, {"paid": mark_paid(razorpay("GET", f"/orders/{o}"))})

    claims = verify_google(data.get("idToken", ""))
    if claims is None:
        return reply(401, {"error": "sign in again"})
    sub = claims["sub"]
    u, st = user_status(sub, claims.get("email", ""))
    # A payment link the user opened: Razorpay is the source of truth, so a missed webhook never loses a purchase.
    link = None
    if not st["paid"] and u.get("linkId"):
        link = razorpay("GET", f"/payment_links/{u['linkId']}")
        if mark_paid(link):
            st["paid"] = True
    if path == "/status":
        return reply(200, st)
    if path == "/order":
        if st["paid"]:
            return reply(200, st)
        if link is None or link.get("status") != "created":
            link = razorpay(
                "POST",
                "/payment_links",
                {
                    "amount": PRICE_PAISE,
                    "currency": "INR",
                    "accept_partial": False,
                    "description": "PremDue lifetime licence",
                    "notify": {"sms": False, "email": False},
                    "reminder_enable": False,
                    "notes": {"sub": sub},
                    "callback_url": ORIGIN + "/premdue/paid.html",
                    "callback_method": "get",
                },
            )
            TABLE.update_item(Key={"sub": sub}, UpdateExpression="SET linkId = :l", ExpressionAttributeValues={":l": link["id"]})
        return reply(200, {**st, "payUrl": link["short_url"]})
    return reply(404, {"error": "not found"})
