#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import re
import sys


def extract_version_section(readme: str, version: str) -> str:
    pattern = re.compile(
        rf"^##\s+{re.escape(version)}\s*$\n(?P<body>.*?)(?=^##\s+|\Z)",
        re.MULTILINE | re.DOTALL,
    )
    match = pattern.search(readme)
    return match.group("body").strip() if match else ""


def main() -> int:
    if len(sys.argv) < 3:
        print("Uso: build_release_notes.py <version> <output> [extra]", file=sys.stderr)
        return 2

    version = sys.argv[1].strip()
    output = pathlib.Path(sys.argv[2])
    extra = sys.argv[3].strip() if len(sys.argv) > 3 else ""
    readme = pathlib.Path("README.md").read_text(encoding="utf-8")
    changes = extract_version_section(readme, version)

    if not changes:
        changes = "- Actualización del ASCENDERSMC Universal Installer."

    body = f"""# ASCENDERSMC Universal Installer {version}

Instalador oficial para preparar y mantener los perfiles **CobbleWorld** y **Pixelmon** de ASCENDERSMC de forma aislada, sin utilizar la carpeta global de mods del jugador.

## Compatibilidad

- CurseForge
- Prism Launcher
- SKLauncher
- Modrinth App
- TLauncher
- Minecraft Launcher

## Cambios de esta versión

{changes}
"""

    if extra:
        body += f"\n## Notas adicionales\n\n{extra}\n"

    body += """
## Descarga segura

La Release incluye `SHA256SUMS.txt`. El propio Installer utiliza ese checksum para validar futuras actualizaciones automáticas antes de reemplazar el JAR instalado.

> ¿Quieres compatibilidad con otro launcher? Crea un ticket indicando el launcher, su versión y tu sistema operativo para que podamos evaluar su integración.
"""

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(body, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
