#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f release/ASCENDERSMC-UNIVERSAL-INSTALLER.jar ] || bash build.sh
mkdir -p .build/test-classes
javac --release 21 -encoding UTF-8 -cp release/ASCENDERSMC-UNIVERSAL-INSTALLER.jar -d .build/test-classes tests/SkLauncher4AutoImportTest.java
java -cp .build/test-classes:release/ASCENDERSMC-UNIVERSAL-INSTALLER.jar com.ascendersmc.installer.launcher.SkLauncher4AutoImportTest
rm -rf .build/test-classes
