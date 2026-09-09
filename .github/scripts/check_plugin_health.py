import os
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request


TIMEOUT = 20

USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 14) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/151.0.0.0 Mobile Safari/537.36"
)


# ============================================================
# CONFIGURAZIONE TEST
# ============================================================

TOONITALIA = {
    "name": "ToonItalia",

    "base_url":
        "https://toonitalia.xyz",

    # Query semplice che dovrebbe produrre risultati
    "search_query":
        "scooby",

    # Pagina che sappiamo essere presente sul sito
    "load_url":
        "https://toonitalia.xyz/"
        "le-allegre-avventure-di-scooby-doo-e-i-suoi-amici/",
}


# ============================================================
# HTTP
# ============================================================

def fetch(url, referer=None):

    headers = {
        "User-Agent": USER_AGENT,
        "Accept": (
            "text/html,"
            "application/xhtml+xml,"
            "application/xml;q=0.9,"
            "*/*;q=0.8"
        ),
        "Accept-Language":
            "it-IT,it;q=0.9,en;q=0.8",
        "Cache-Control":
            "no-cache",
    }

    if referer:
        headers["Referer"] = referer

    request = urllib.request.Request(
        url,
        headers=headers
    )

    context = ssl.create_default_context()

    start = time.time()

    try:

        with urllib.request.urlopen(
            request,
            timeout=TIMEOUT,
            context=context
        ) as response:

            body = response.read().decode(
                "utf-8",
                errors="ignore"
            )

            elapsed = round(
                (time.time() - start) * 1000
            )

            return {
                "ok": True,
                "blocked": False,
                "code": response.getcode(),
                "time": elapsed,
                "url": response.geturl(),
                "body": body,
                "error": ""
            }

    except urllib.error.HTTPError as e:

        elapsed = round(
            (time.time() - start) * 1000
        )

        # Non consideriamo Cloudflare/CAPTCHA
        # come rottura del plugin.
        if e.code in (
            401,
            403,
            429
        ):
            return {
                "ok": False,
                "blocked": True,
                "code": e.code,
                "time": elapsed,
                "url": url,
                "body": "",
                "error":
                    "Possibile Cloudflare / CAPTCHA"
            }

        return {
            "ok": False,
            "blocked": False,
            "code": e.code,
            "time": elapsed,
            "url": url,
            "body": "",
            "error":
                f"HTTP {e.code}"
        }

    except Exception as e:

        elapsed = round(
            (time.time() - start) * 1000
        )

        return {
            "ok": False,
            "blocked": False,
            "code": "-",
            "time": elapsed,
            "url": url,
            "body": "",
            "error": str(e)
        }


# ============================================================
# UTILITÀ
# ============================================================

def has_class(html, class_name):

    pattern = (
        r'class=["\'][^"\']*\b'
        + re.escape(class_name)
        + r'\b[^"\']*["\']'
    )

    return bool(
        re.search(
            pattern,
            html,
            re.IGNORECASE
        )
    )


def contains_pattern(
    html,
    pattern
):

    return bool(
        re.search(
            pattern,
            html,
            re.IGNORECASE |
            re.DOTALL
        )
    )


def result_status(
    passed,
    response=None
):

    if response:

        if response["blocked"]:
            return "🛡️ BLOCCATO"

        if not response["ok"]:
            return "❌ ERRORE"

    if passed:
        return "✅ OK"

    return "❌ ROTTO"


# ============================================================
# TOONITALIA - HOMEPAGE
# ============================================================

def test_toonitalia_homepage():

    url = TOONITALIA[
        "base_url"
    ]

    response = fetch(url)

    if response["blocked"]:
        return {
            "status":
                "🛡️ BLOCCATO",
            "details":
                response["error"],
            "time":
                response["time"]
        }

    if not response["ok"]:
        return {
            "status":
                "❌ ERRORE",
            "details":
                response["error"],
            "time":
                response["time"]
        }

    html = response["body"]

    checks = {
        "grid":
            has_class(
                html,
                "grid"
            ),

        "col":
            has_class(
                html,
                "col"
            ),

        "item":
            has_class(
                html,
                "item"
            ),

        "card-link":
            has_class(
                html,
                "card-link"
            ),
    }

    passed = all(
        checks.values()
    )

    missing = [
        name
        for name, ok
        in checks.items()
        if not ok
    ]

    return {
        "status":
            result_status(
                passed,
                response
            ),

        "details":
            (
                "Struttura homepage valida"
                if passed
                else
                "Mancano: "
                + ", ".join(missing)
            ),

        "time":
            response["time"]
    }


# ============================================================
# TOONITALIA - RICERCA
# ============================================================

def test_toonitalia_search():

    query = urllib.parse.quote(
        TOONITALIA[
            "search_query"
        ]
    )

    url = (
        TOONITALIA[
            "base_url"
        ]
        + "/?s="
        + query
    )

    response = fetch(
        url,
        referer=
            TOONITALIA[
                "base_url"
            ]
    )

    if response["blocked"]:
        return {
            "status":
                "🛡️ BLOCCATO",
            "details":
                response["error"],
            "time":
                response["time"]
        }

    if not response["ok"]:
        return {
            "status":
                "❌ ERRORE",
            "details":
                response["error"],
            "time":
                response["time"]
        }

    html = response["body"]

    # Cerchiamo almeno un link interno
    # che assomigli a una pagina contenuto.
    internal_links = re.findall(
        r'href=["\']'
        r'(https?://toonitalia\.xyz/'
        r'[^"\']+)'
        r'["\']',
        html,
        re.IGNORECASE
    )

    filtered_links = []

    for link in internal_links:

        lower = link.lower()

        if any(
            part in lower
            for part in (
                "/wp-",
                "/feed",
                "/category/",
                "/tag/",
                "/author/",
                "?s="
            )
        ):
            continue

        filtered_links.append(
            link
        )

    has_results = (
        len(
            set(filtered_links)
        )
        > 0
    )

    # Controllo aggiuntivo:
    # la query deve comparire almeno
    # da qualche parte nella pagina.
    has_query_reference = (
        "scooby"
        in html.lower()
    )

    passed = (
        has_results
        and has_query_reference
    )

    return {
        "status":
            result_status(
                passed,
                response
            ),

        "details":
            (
                f"Trovati "
                f"{len(set(filtered_links))} "
                "link interni"
                if passed
                else
                "Nessun risultato valido rilevato"
            ),

        "time":
            response["time"]
    }


# ============================================================
# TOONITALIA - LOAD
# ============================================================

def test_toonitalia_load():

    url = TOONITALIA[
        "load_url"
    ]

    response = fetch(
        url,
        referer=
            TOONITALIA[
                "base_url"
            ]
    )

    if response["blocked"]:
        return {
            "status":
                "🛡️ BLOCCATO",
            "details":
                response["error"],
            "time":
                response["time"]
        }

    if not response["ok"]:
        return {
            "status":
                "❌ ERRORE",
            "details":
                response["error"],
            "time":
                response["time"]
        }

    html = response["body"]

    checks = {
        "entry-content":
            has_class(
                html,
                "entry-content"
            ),

        "immagine":
            contains_pattern(
                html,
                r'<img\b'
            ),

        "link":
            contains_pattern(
                html,
                r'<a\b[^>]+href='
            ),
    }

    passed = all(
        checks.values()
    )

    missing = [
        name
        for name, ok
        in checks.items()
        if not ok
    ]

    return {
        "status":
            result_status(
                passed,
                response
            ),

        "details":
            (
                "Pagina contenuto valida"
                if passed
                else
                "Mancano: "
                + ", ".join(missing)
            ),

        "time":
            response["time"]
    }


# ============================================================
# MAIN
# ============================================================

def main():

    print()
    print(
        "========================================"
    )
    print(
        "      DANIX PLUGIN HEALTH"
    )
    print(
        "========================================"
    )
    print()

    homepage = (
        test_toonitalia_homepage()
    )

    search = (
        test_toonitalia_search()
    )

    load = (
        test_toonitalia_load()
    )

    results = {
        "Homepage": homepage,
        "Ricerca": search,
        "Load": load
    }

    print(
        "🐉 ToonItalia"
    )

    for name, result in (
        results.items()
    ):

        print(
            f"   {name}: "
            f"{result['status']} "
            f"({result['time']} ms)"
        )

        print(
            f"      {result['details']}"
        )

    print()

    # --------------------------------------------------------
    # GitHub Actions Summary
    # --------------------------------------------------------

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
                "\n# 🧪 Plugin Health\n\n"
            )

            summary.write(
                "## 🐉 ToonItalia\n\n"
            )

            summary.write(
                "| Test | Stato | Tempo | Dettagli |\n"
            )

            summary.write(
                "|---|---|---:|---|\n"
            )

            for name, result in (
                results.items()
            ):

                details = (
                    str(
                        result[
                            "details"
                        ]
                    )
                    .replace(
                        "|",
                        "\\|"
                    )
                )

                summary.write(
                    f"| {name} | "
                    f"{result['status']} | "
                    f"{result['time']} ms | "
                    f"{details} |\n"
                )


if __name__ == "__main__":
    main()
