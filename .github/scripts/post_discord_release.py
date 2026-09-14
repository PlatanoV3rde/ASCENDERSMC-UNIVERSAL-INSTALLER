#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request


def env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Falta la variable requerida {name}")
    return value


def read_changes(path: str) -> str:
    text = pathlib.Path(path).read_text(encoding="utf-8")
    marker = "## Cambios de esta versión"
    start = text.find(marker)
    if start < 0:
        return "Mejoras internas y ajustes del Installer."
    body = text[start + len(marker):]
    next_heading = body.find("\n## ")
    if next_heading >= 0:
        body = body[:next_heading]
    body = body.strip()
    return body[:950] + ("…" if len(body) > 950 else "")


def with_components(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    query = dict(urllib.parse.parse_qsl(parsed.query, keep_blank_values=True))
    query["with_components"] = "true"
    query["wait"] = "true"
    return urllib.parse.urlunsplit((
        parsed.scheme,
        parsed.netloc,
        parsed.path,
        urllib.parse.urlencode(query),
        parsed.fragment,
    ))


def main() -> int:
    webhook = env("DISCORD_DOWNLOADS_WEBHOOK")
    version = env("RELEASE_VERSION")
    download_url = env("DOWNLOAD_URL")
    notes_file = env("RELEASE_NOTES_FILE")
    changes = read_changes(notes_file)

    payload = {
        "username": "ASCENDERSMC DESCARGAS",
        "allowed_mentions": {"parse": []},
        "embeds": [
            {
                "title": f"ASCENDERSMC UNIVERSAL INSTALLER • v{version}",
                "description": (
                    "La instalación oficial de **ASCENDERSMC** en un solo lugar. Selecciona **CobbleWorld** o **Pixelmon**, "
                    "elige tu launcher y el Installer prepara un perfil independiente con la versión correcta de Minecraft y NeoForge, "
                    "descarga el modpack y coloca el resource pack correspondiente.\n\n"
                    "Cuando ASCENDERSMC publique cambios, vuelve a ejecutar el Installer: el perfil se sincroniza con la distribución oficial, "
                    "incluyendo **mods nuevos, actualizados o retirados**, sin mezclar archivos con otros perfiles del jugador. "
                    "El propio Installer también puede mantenerse actualizado sin tener que descargar manualmente cada nueva versión."
                ),
                "color": 0x6D5DFB,
                "fields": [
                    {
                        "name": "🎮 PERFILES ASCENDERSMC",
                        "value": "**CobbleWorld**  •  **Pixelmon**",
                        "inline": False,
                    },
                    {
                        "name": "🚀 LAUNCHERS COMPATIBLES",
                        "value": (
                            "**CurseForge** • **Prism Launcher** • **SKLauncher**\n"
                            "**Modrinth App** • **TLauncher** • **Minecraft Launcher**"
                        ),
                        "inline": False,
                    },
                    {
                        "name": f"✨ NOVEDADES DE v{version}",
                        "value": changes,
                        "inline": False,
                    },
                    {
                        "name": "🧩 ¿QUIERES SOPORTE PARA OTRO LAUNCHER?",
                        "value": (
                            "Abre un **ticket** e indica el nombre del launcher, su versión y tu sistema operativo. "
                            "Revisaremos la viabilidad de añadir compatibilidad en una futura versión."
                        ),
                        "inline": False,
                    },
                ],
                "footer": {
                    "text": "ASCENDERSMC • INSTALADOR OFICIAL"
                },
            }
        ],
        "components": [
            {
                "type": 1,
                "components": [
                    {
                        "type": 2,
                        "style": 5,
                        "label": f"DESCARGAR INSTALLER v{version}",
                        "url": download_url,
                    }
                ],
            }
        ],
    }

    if os.environ.get("DISCORD_DRY_RUN", "").strip().lower() in {"1", "true", "yes"}:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0

    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        with_components(webhook),
        data=data,
        headers={"Content-Type": "application/json", "User-Agent": "ASCENDERSMC-Release-Workflow/1.1"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status not in (200, 204):
                raise RuntimeError(f"Discord respondió HTTP {response.status}")
    except urllib.error.HTTPError as exc:
        body = ""
        try:
            body = exc.read().decode("utf-8", errors="replace")[:1000]
        except Exception:
            pass
        raise RuntimeError(f"Discord respondió HTTP {exc.code}: {body}") from exc

    print(f"Anuncio de v{version} enviado correctamente a Discord.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Error publicando en Discord: {exc}", file=sys.stderr)
        raise SystemExit(1)
