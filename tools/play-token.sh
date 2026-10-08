#!/bin/zsh
# Prints a Google Play Developer API access token for the service account key.
#   tools/play-token.sh [key.json]   default: ~/.giga-pisar-keys/play-service-account.json
set -e
KEY=${1:-$HOME/.giga-pisar-keys/play-service-account.json}
b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
EMAIL=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['client_email'])" "$KEY")
NOW=$(date +%s)
HEADER=$(printf '{"alg":"RS256","typ":"JWT"}' | b64)
CLAIM=$(printf '{"iss":"%s","scope":"https://www.googleapis.com/auth/androidpublisher","aud":"https://oauth2.googleapis.com/token","iat":%d,"exp":%d}' "$EMAIL" $NOW $((NOW+3600)) | b64)
PEM=$(mktemp); trap 'rm -f $PEM' EXIT; chmod 600 $PEM
python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['private_key'])" "$KEY" > $PEM
SIG=$(printf '%s.%s' "$HEADER" "$CLAIM" | openssl dgst -sha256 -sign $PEM -binary | b64)
curl -s https://oauth2.googleapis.com/token -d grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer -d assertion="$HEADER.$CLAIM.$SIG" \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('access_token') or ('ERROR '+json.dumps(d)))"
