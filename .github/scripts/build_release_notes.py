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
    repo_root = pathlib.Path(__file__).resolve().parents[2]
    readme = (repo_root / "README.md").read_text(encoding="utf-8")
    changes = extract_version_section(readme, version)

    if not changes:
        changes = (
            "- Mejoras de estabilidad y mantenimiento del Installer.\n"
            "- Compatibilidad conservada con los launchers y perfiles oficiales de ASCENDERSMC."
        )

    body = f"""# ASCENDERSMC UNIVERSAL INSTALLER {version}

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
## Solicitudes de compatibilidad

¿Quieres compatibilidad con otro launcher? Crea un ticket indicando el launcher, su versión y tu sistema operativo para que podamos evaluar su integración.
"""

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(body, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
