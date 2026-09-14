# Configuración de publicación

Repositorio de Releases:

```text
PlatanoV3rde/ASCENDERSMC-UNIVERSAL-INSTALLER
```

## Secrets requeridos

En GitHub abre:

**Settings → Secrets and variables → Actions → New repository secret**

Crea exactamente:

```text
DIST_REPO_TOKEN
DISCORD_DOWNLOADS_WEBHOOK
```

### DIST_REPO_TOKEN

Token con acceso al repositorio `PlatanoV3rde/ASCENDERSMC-UNIVERSAL-INSTALLER` y permiso para administrar **Contents / Releases**.

### DISCORD_DOWNLOADS_WEBHOOK

URL del incoming webhook del canal de Discord donde se anunciarán las Releases.

Nunca guardes los valores de estos secretos en Java, `installer.properties`, YAML, README ni commits.

## Publicar una versión

1. Cambia `App.VERSION`, por ejemplo de `0.8.2` a `0.8.2`.
2. Añade el changelog bajo `## 0.8.2` en `README.md`.
3. Haz commit y push.
4. Ejecuta **Actions → Publicar ASCENDERSMC UNIVERSAL INSTALLER → Run workflow**.

También puedes publicar creando y subiendo un tag que coincida exactamente con la versión:

```text
v0.8.2
```

El workflow:

1. compila con Java 21;
2. valida `SHA256SUMS.txt`;
3. crea el ZIP del código fuente;
4. crea la GitHub Release;
5. adjunta JAR + checksum + source ZIP;
6. marca la Release como Latest;
7. envía el anuncio profesional a Discord.

Una versión ya publicada no puede sustituirse silenciosamente con otro JAR. Si el tag existe y el binario local es diferente, el workflow falla y obliga a incrementar `App.VERSION`. Esto es necesario porque el auto-updater compara versiones y valida el SHA-256 publicado.

## Auto-update

Los clientes consultan la última Release pública. No necesitan ningún PAT ni webhook. El Installer descarga el JAR y `SHA256SUMS.txt`, valida SHA-256, reemplaza el JAR desde un proceso helper y se relanza manteniendo la misma ubicación.
