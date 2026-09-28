#!/usr/bin/env bash
# Entfernt den Prototyp aus dem aktuellen OpenShift-Projekt (`oc project` vorher waehlen) - das
# Gegenstueck zu deploy.sh.
#
#   ./openshift/undeploy.sh            Deployment, Service, Routes, ConfigMap, Builds und Images
#                                      entfernen; die Daten (PVCs) und das Admin-Secret bleiben.
#   ./openshift/undeploy.sh --purge    zusaetzlich PVCs und Secret: Konten, Aenderungsprotokoll,
#                                      Realm und Admin-Passwort sind danach weg. Nicht umkehrbar.
#   --yes                              ohne Rueckfrage (etwa in einem Skript)
#
# Ohne --purge findet ein spaeteres deploy.sh die Daten wieder vor. Die Route-Hosts vergibt
# OpenShift dann neu, nach demselben Muster aus Name und Projekt, also in der Regel gleich; aendern
# sie sich doch, baut die Migration das Realm neu auf (README).
set -euo pipefail
cd "$(dirname "$0")/.."

purge=0
yes=0
for arg in "$@"; do
  case "$arg" in
    --purge) purge=1 ;;
    --yes) yes=1 ;;
    *) echo "Unbekannte Option: $arg (erlaubt: --purge, --yes)" >&2; exit 2 ;;
  esac
done

command -v oc >/dev/null || { echo "oc nicht gefunden" >&2; exit 1; }

project=$(oc project -q)
echo "==> Projekt: $project"

if [ "$yes" != 1 ]; then
  if [ "$purge" = 1 ]; then
    echo "Entfernt identity-demo samt aller Daten (PVCs, Admin-Secret). Nicht umkehrbar."
    read -r -p "Zur Bestaetigung den Projektnamen eingeben: " answer
    [ "$answer" = "$project" ] || { echo "Abgebrochen." >&2; exit 1; }
  else
    read -r -p "identity-demo aus '$project' entfernen (Daten bleiben)? [j/N] " answer
    case "$answer" in j|J|ja|Ja) ;; *) echo "Abgebrochen." >&2; exit 1 ;; esac
  fi
fi

# Erst was laeuft, dann was es baut: ohne Deployment startet keine ImageStream-Aenderung mehr einen Pod.
echo "==> Deployment, Service, Routes, ConfigMap"
oc delete deployment/identity-demo --ignore-not-found --wait=true
oc delete route/identity-demo-orchestrator route/identity-demo-keycloak --ignore-not-found
oc delete service/identity-demo configmap/identity-demo-env --ignore-not-found

echo "==> Builds und Images"
# Auch die Builds der BuildConfigs: ein laufender Build haelt sonst Quota und schreibt spaeter noch
# in einen ImageStream, den es nicht mehr gibt.
for build_config in identity-demo-keycloak identity-demo-orchestrator; do
  oc delete builds -l "openshift.io/build-config.name=$build_config" --ignore-not-found
done
oc delete buildconfig/identity-demo-keycloak buildconfig/identity-demo-orchestrator --ignore-not-found
oc delete imagestream/identity-demo-keycloak imagestream/identity-demo-orchestrator --ignore-not-found

if [ "$purge" = 1 ]; then
  echo "==> Daten und Admin-Secret"
  oc delete pvc/identity-demo-keycloak-data pvc/identity-demo-orchestrator-data --ignore-not-found --wait=true
  oc delete secret/identity-demo-keycloak-admin --ignore-not-found
  echo
  echo "Entfernt, einschliesslich aller Daten."
else
  echo
  echo "Entfernt. Erhalten: pvc/identity-demo-keycloak-data, pvc/identity-demo-orchestrator-data,"
  echo "secret/identity-demo-keycloak-admin - ./openshift/deploy.sh setzt darauf wieder auf,"
  echo "./openshift/undeploy.sh --purge entfernt auch sie."
fi
