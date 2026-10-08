#!/bin/zsh
# Uploads the Google Play store listing (texts, icon, feature graphic, screenshots) from play/listing.
# Needs the service account to have "Manage store presence" (and "Edit and delete draft apps" while the app is a draft).
set -e
cd "$(dirname "$0")/.."
T=$(tools/play-token.sh)
API=https://androidpublisher.googleapis.com/androidpublisher/v3/applications/ru.gigapisar
UP=https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/ru.gigapisar
ID=$(curl -s -X POST -H "Authorization: Bearer $T" $API/edits | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
for L in ru-RU en-US; do
  curl -sf -X PUT -H "Authorization: Bearer $T" -H "Content-Type: application/json" --data-binary @play/listing/$L.json "$API/edits/${ID}/listings/$L" -o /dev/null
  for t in icon featureGraphic phoneScreenshots; do curl -s -X DELETE -H "Authorization: Bearer $T" "$API/edits/${ID}/listings/$L/$t" -o /dev/null; done
  up() { curl -sf -X POST -H "Authorization: Bearer $T" -H "Content-Type: image/png" --data-binary @$2 "$UP/edits/${ID}/listings/$L/$1?uploadType=media" -o /dev/null; }
  up icon play/listing/icon.png
  up featureGraphic play/listing/feature.png
  for s in play/listing/screen-*.png; do up phoneScreenshots $s; done
  echo "listing $L ready"
done
curl -s -X POST -H "Authorization: Bearer $T" "${API}/edits/${ID}:commit"
