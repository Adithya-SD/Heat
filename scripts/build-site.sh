#!/bin/sh
# Builds the public Heat site (site/) for its own Vercel project: the Heat web page exactly as it is in public/, plus the
# signed phone and watch APKs in download/ (the page shows its "Heat for Android" card when download/Heat.apk exists).
#   APKs come from android/dist/ (./gradlew :phone:assembleRelease :wear:assembleRelease -PheatDist, then copy)
# Deploy from site/:  vercel deploy --prod
set -e
cd "$(dirname "$0")/.."
find site -mindepth 1 -maxdepth 1 ! -name vercel.json ! -name .gitignore ! -name .vercel ! -name download -exec rm -rf {} +
for f in public/*; do [ "$(basename "$f")" = download ] || cp -R "$f" site/; done
mkdir -p site/download
cp android/dist/Heat-phone.apk site/download/Heat.apk
cp android/dist/Heat-watch.apk site/download/Heat-watch.apk
ls -la site/download
