#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import pathlib
import sys
import urllib.error
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
        return "Consulta la Release para ver todos los cambios."
    body = text[start + len(marker):]
    next_heading = body.find("\n## ")
    if next_heading >= 0:
        body = body[:next_heading]
    body = body.strip()
    # Discord limita los fields; mantenemos el changelog compacto y enlazamos la Release.
    return body[:950] + ("…" if len(body) > 950 else "")


def main() -> int:
    webhook = env("DISCORD_DOWNLOADS_WEBHOOK")
    version = env("RELEASE_VERSION")
    release_url = env("RELEASE_URL")
    download_url = env("DOWNLOAD_URL")
    notes_file = env("RELEASE_NOTES_FILE")
    changes = read_changes(notes_file)

    payload = {
        "username": "ASCENDERSMC Downloads",
        "allowed_mentions": {"parse": []},
        "embeds": [
            {
                "title": f"ASCENDERSMC Universal Installer • v{version}",
                "url": release_url,
                "description": (
                    "**ASCENDERSMC Universal Installer** centraliza la instalación y el mantenimiento de los perfiles oficiales "
                    "de **CobbleWorld** y **Pixelmon**. Detecta el launcher compatible, prepara un perfil aislado, instala o "
                    "configura NeoForge cuando corresponde y mantiene sincronizados el modpack y el resource pack sin utilizar "
                    "la carpeta global de mods del jugador.\n\n"
                    "Las nuevas versiones del Installer se obtienen desde **GitHub Releases** y se validan mediante **SHA-256** "
                    "antes de aplicar la actualización automática."
                ),
                "color": 0x6D5DFB,
                "fields": [
                    {
                        "name": "Launchers compatibles",
                        "value": "CurseForge • Prism Launcher • SKLauncher\nModrinth App • TLauncher • Minecraft Launcher",
                        "inline": False,
                    },
                    {
                        "name": "Perfiles disponibles",
                        "value": "CobbleWorld • Pixelmon",
                        "inline": True,
                    },
                    {
                        "name": "Actualización de contenido",
                        "value": "Agrega, reemplaza o elimina mods según la Release y actualiza el resource pack administrado.",
                        "inline": True,
                    },
                    {
                        "name": "Cambios de esta versión",
                        "value": changes or "Consulta la Release para ver todos los cambios.",
                        "inline": False,
                    },
                    {
                        "name": "¿Falta tu launcher?",
                        "value": "Crea un **ticket** indicando el launcher, su versión y tu sistema operativo para solicitar que evaluemos compatibilidad.",
                        "inline": False,
                    },
                    {
                        "name": "Descarga",
                        "value": f"[Descargar Installer v{version}]({download_url}) • [Ver Release y checksums]({release_url})",
                        "inline": False,
                    },
                ],
                "footer": {
                    "text": "ASCENDERSMC • Distribución oficial desde GitHub Releases"
                },
            }
        ],
    }

    if os.environ.get("DISCORD_DRY_RUN", "").strip().lower() in {"1", "true", "yes"}:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0

    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        webhook,
        data=data,
        headers={"Content-Type": "application/json", "User-Agent": "ASCENDERSMC-Release-Workflow/1.0"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status not in (200, 204):
                raise RuntimeError(f"Discord respondió HTTP {response.status}")
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"Discord respondió HTTP {exc.code}") from exc

    print(f"Anuncio de v{version} enviado correctamente a Discord.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Error publicando en Discord: {exc}", file=sys.stderr)
        raise SystemExit(1)
