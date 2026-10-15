#!/bin/sh
# Builds the simple Heat site (site/) for deploying as its own Vercel project:
#   site/app/       a copy of the full web page (public/, without its own download folder), so "Watch in browser" works
#   site/download/  the signed phone and watch APKs (android/dist/, built with: ./gradlew :phone:assembleRelease :wear:assembleRelease -PheatDist)
# Then, from site/:  vercel deploy --prod
set -e
cd "$(dirname "$0")/.."
rm -rf site/app
mkdir -p site/app site/download
for f in public/*; do [ "$(basename "$f")" = download ] || cp -R "$f" site/app/; done
cp android/dist/Heat-phone.apk site/download/Heat.apk
cp android/dist/Heat-watch.apk site/download/Heat-watch.apk
ls -la site/download
