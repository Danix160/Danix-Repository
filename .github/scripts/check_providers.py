import os
import re
import ssl
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

TIMEOUT = 15

USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 14) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/151.0.0.0 Mobile Safari/537.36"
)


def find_providers():
    providers = []

    for file in ROOT.rglob("*.kt"):

        # Evitiamo cartelle generate
        if any(
            part in {
                "build",
                ".gradle",
                ".git"
            }
            for part in file.parts
        ):
            continue

        try:
            text = file.read_text(
                encoding="utf-8",
                errors="ignore"
            )
        except Exception:
            continue

        # Deve sembrare un MainAPI/provider CloudStream
        if "MainAPI" not in text:
            continue

        main_url_match = re.search(
            r'override\s+(?:var|val)\s+mainUrl\s*=\s*"([^"]+)"',
            text
        )

        if not main_url_match:
            continue

        url = main_url_match.group(1).strip()

        name_match = re.search(
            r'override\s+(?:var|val)\s+name\s*=\s*"([^"]+)"',
            text
        )

        if name_match:
            name = name_match.group(1).strip()
        else:
            name = file.stem

        providers.append(
            {
                "name": name,
                "url": url,
                "file": str(
                    file.relative_to(ROOT)
                )
            }
        )

    # Rimuove eventuali duplicati
    unique = {}

    for provider in providers:
        key = (
            provider["name"].lower(),
            provider["url"].lower()
        )

        unique[key] = provider

    return sorted(
        unique.values(),
        key=lambda x: x["name"].lower()
    )


def check_url(url):
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": (
                "text/html,"
                "application/xhtml+xml,"
                "application/xml;q=0.9,"
                "*/*;q=0.8"
            ),
            "Accept-Language": "it-IT,it;q=0.9,en;q=0.8",
            "Cache-Control": "no-cache",
        }
    )

    context = ssl.create_default_context()

    start = time.time()

    try:

        with urllib.request.urlopen(
            request,
            timeout=TIMEOUT,
            context=context
        ) as response:

            code = response.getcode()
            final_url = response.geturl()

            elapsed = round(
                (time.time() - start) * 1000
            )

            if 200 <= code < 400:
                return {
                    "status": "✅ ONLINE",
                    "code": code,
                    "time": elapsed,
                    "final_url": final_url,
                    "note": ""
                }

            return {
                "status": "⚠️ PARZIALE",
                "code": code,
                "time": elapsed,
                "final_url": final_url,
                "note": "Risposta HTTP anomala"
            }

    except urllib.error.HTTPError as e:

    elapsed = round(
        (time.time() - start) * 1000
    )

    # Redirect: il dominio risponde, quindi non è offline.
    if e.code in (301, 302, 303, 307, 308):

        location = e.headers.get(
            "Location",
            ""
        )

        return {
            "status": "🔀 REDIRECT",
            "code": e.code,
            "time": elapsed,
            "final_url": location or url,
            "note": (
                f"Redirect verso {location}"
                if location
                else "Redirect HTTP"
            )
        }

    # Cloudflare, CAPTCHA, rate limit o autenticazione.
    # Il dominio esiste e sta rispondendo.
    if e.code in (401, 403, 429):
        return {
            "status": "🛡️ PROTETTO",
            "code": e.code,
            "time": elapsed,
            "final_url": url,
            "note": "Possibile CAPTCHA / Cloudflare"
        }

    if 400 <= e.code < 500:
        return {
            "status": "⚠️ PARZIALE",
            "code": e.code,
            "time": elapsed,
            "final_url": url,
            "note": "Errore HTTP"
        }

    return {
        "status": "❌ OFFLINE",
        "code": e.code,
        "time": elapsed,
        "final_url": url,
        "note": "Errore server"
    }

    except urllib.error.URLError as e:

        elapsed = round(
            (time.time() - start) * 1000
        )

        return {
            "status": "❌ OFFLINE",
            "code": "-",
            "time": elapsed,
            "final_url": url,
            "note": str(e.reason)
        }

    except Exception as e:

        elapsed = round(
            (time.time() - start) * 1000
        )

        return {
            "status": "❌ ERRORE",
            "code": "-",
            "time": elapsed,
            "final_url": url,
            "note": str(e)
        }


def escape_markdown(text):
    return str(text).replace("|", "\\|")


def main():

    providers = find_providers()

    print()
    print("========================================")
    print("       DANIX PROVIDER STATUS")
    print("========================================")
    print()

    if not providers:
        print("❌ Nessun provider trovato.")
        return

    results = []

    for provider in providers:

        print(
            f"🔎 {provider['name']}"
        )

        print(
            f"   {provider['url']}"
        )

        result = check_url(
            provider["url"]
        )

        results.append(
            {
                **provider,
                **result
            }
        )

        print(
            f"   {result['status']} "
            f"HTTP {result['code']} "
            f"({result['time']} ms)"
        )

        if result["note"]:
            print(
                f"   ℹ️ {result['note']}"
            )

        print()

    online = sum(
    1 for r in results
    if r["status"] == "✅ ONLINE"
)

protected = sum(
    1 for r in results
    if r["status"] == "🛡️ PROTETTO"
)

redirect = sum(
    1 for r in results
    if r["status"] == "🔀 REDIRECT"
)

partial = sum(
    1 for r in results
    if r["status"] == "⚠️ PARZIALE"
)

offline = sum(
    1 for r in results
    if r["status"].startswith("❌")
)

    print("========================================")
    print(
        f"✅ Online: {online}"
    )
    print(
        f"🛡️ Protetti: {protected}"
    )
    print(
        f"⚠️ Parziali: {partial}"
    )
    print(
        f"🔀 Redirect: {redirect}"
    )
    print(
        f"❌ Offline: {offline}"
    )
    print("========================================")

    # GitHub Actions Job Summary
    summary_file = os.environ.get(
        "GITHUB_STEP_SUMMARY"
    )

    if summary_file:

        with open(
            summary_file,
            "a",
            encoding="utf-8"
        ) as summary:

            summary.write(
                "# 🩺 Danix Repository — Provider Status\n\n"
            )

            summary.write(
                f"**Provider rilevati:** {len(results)}  \n"
            )

            summary.write(
            f"✅ **Online:** {online} · "
            f"🛡️ **Protetti:** {protected} · "
            f"🔀 **Redirect:** {redirect} · "
            f"⚠️ **Parziali:** {partial} · "
            f"❌ **Offline:** {offline}\n\n"
        )

            summary.write(
                "| Plugin | Stato | HTTP | Tempo | Dominio |\n"
            )

            summary.write(
                "|---|---|---:|---:|---|\n"
            )

            for item in results:

                summary.write(
                    "| "
                    f"{escape_markdown(item['name'])} | "
                    f"{escape_markdown(item['status'])} | "
                    f"{item['code']} | "
                    f"{item['time']} ms | "
                    f"{escape_markdown(item['url'])} |\n"
                )


if __name__ == "__main__":
    main()
