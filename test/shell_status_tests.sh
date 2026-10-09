#!/usr/bin/env bash
# Verifie le COMPORTEMENT reel du motif de capture de statut utilise par
# ScannerRunner.runSonar(), dans le meme shell que Jenkins (/bin/sh, dash sur
# cette image) et avec xtrace active, comme Jenkins le fait (`sh -xe`).
#
# Le harnais Groovy verifie le contrat du script genere ; celui-ci verifie que
# le motif propage reellement le statut, ce qu'aucune assertion de texte ne
# peut prouver.
set -u
PASS=0; FAIL=0
ok()  { echo "PASS: $1"; PASS=$((PASS+1)); }
ko()  { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

# Reproduit exactement le motif de runSonar, avec une commande factice a la
# place de `mvn`. `-xe` reproduit l'invocation de Jenkins.
run_pattern() {
    exit_code="$1"
    cat > "$WORK/script.sh" <<INNER
set -e
set +e
sh -c 'echo "ligne de journal de l analyse"; echo "erreur simulee" >&2; exit $exit_code' > sonar-analysis.log 2>&1
SONAR_STATUS=\$?
set -e
cat sonar-analysis.log
if [ "\$SONAR_STATUS" -ne 0 ]; then
  echo "SONAR_ANALYSIS_FAILED exit=\$SONAR_STATUS"
  exit "\$SONAR_STATUS"
fi
INNER
    ( cd "$WORK" && /bin/sh -xe script.sh > stdout.txt 2> trace.txt )
    echo $?
}

# ── Cas 1 : Maven reussit -> le motif reussit ─────────────────────────────
status=$(run_pattern 0)
[ "$status" = "0" ] && ok "statut 0 de Maven -> le script reussit" \
                    || ko "statut 0 de Maven -> attendu 0, obtenu $status"
grep -q "ligne de journal de l analyse" "$WORK/sonar-analysis.log" \
    && ok "le journal est bien ecrit dans sonar-analysis.log" \
    || ko "sonar-analysis.log ne contient pas la sortie de l analyse"
grep -q "ligne de journal de l analyse" "$WORK/stdout.txt" \
    && ok "le journal est reaffiche sur la sortie standard (lisible dans la console)" \
    || ko "le journal n est pas reaffiche"
grep -q "erreur simulee" "$WORK/sonar-analysis.log" \
    && ok "stderr de l analyse est capture dans le journal (2>&1 conserve)" \
    || ko "stderr de l analyse n est pas capture"

# ── Cas 2 : Maven echoue -> le motif echoue avec le MEME code ─────────────
for code in 1 2 137; do
    status=$(run_pattern "$code")
    [ "$status" = "$code" ] && ok "statut $code de Maven -> le script echoue avec $code" \
                            || ko "statut $code de Maven -> attendu $code, obtenu $status"
done
grep -q "SONAR_ANALYSIS_FAILED exit=137" "$WORK/stdout.txt" \
    && ok "l echec est annonce explicitement, avec son code" \
    || ko "l echec n est pas annonce"

# ── Cas 3 : controle -- l ANCIEN motif masquait bien l echec ──────────────
# Sans cela, on ne prouverait pas que la regression etait reelle.
cat > "$WORK/old.sh" <<'INNER'
set -e
sh -c 'echo sortie; exit 1' 2>&1 | tee sonar-analysis.log
INNER
( cd "$WORK" && /bin/sh -xe old.sh >/dev/null 2>&1 ); old_status=$?
[ "$old_status" = "0" ] \
    && ok "controle : l ancien motif 'mvn | tee' renvoyait bien 0 malgre l echec" \
    || ko "controle : l ancien motif a renvoye $old_status, la regression n est pas reproduite"

# ── Cas 4 : le secret n est pas developpe par xtrace via un settings ──────
# On reproduit la forme retenue : la cle vient de l environnement, le fichier
# ne porte qu une reference, la ligne de commande n en porte aucune trace.
export NVD_API_KEY="VALEUR-DE-TEST-NON-SECRETE"
cat > "$WORK/nvd.sh" <<'INNER'
set -e
SETTINGS_NVD="settings-test.xml"
cat > "$SETTINGS_NVD" <<'ODC_SETTINGS'
<settings><profiles><profile><id>p</id>
<properties><nvdApiKey>${env.NVD_API_KEY}</nvdApiKey></properties>
</profile></profiles></settings>
ODC_SETTINGS
sh -c 'echo "faux mvn avec -s $1"' _ "$SETTINGS_NVD"
INNER
( cd "$WORK" && /bin/sh -xe nvd.sh > nvd.out 2> nvd.trace )
grep -q "VALEUR-DE-TEST-NON-SECRETE" "$WORK/nvd.trace" \
    && ko "la trace xtrace contient la valeur du secret" \
    || ok "la trace xtrace ne contient jamais la valeur du secret"
grep -q "VALEUR-DE-TEST-NON-SECRETE" "$WORK/settings-test.xml" \
    && ko "le fichier de settings contient la valeur du secret" \
    || ok "le fichier de settings ne porte qu une reference, jamais la valeur"

echo

# ══════════════════════════════════════════════════════════════════════════════
# R58 — le verdict de provenance se lit dans le CORPS, pas dans le code HTTP.
#
# Le build #10 a fabrique un succes : le backend a repondu 201 (NestJS @Post
# repond 201 meme pour un refus) avec
# {"status":"REJECTED","failureCode":"DEPLOY_COMMIT_MISSING"}, et le
# `case "$CODE" in 2*) exit 0` l'a pris pour un enregistrement reussi.
#
# La logique testee est EXTRAITE de AcrPublisher.groovy, pas recopiee : le test
# suit donc le vrai code.
# ══════════════════════════════════════════════════════════════════════════════
LIB_ROOT="${LIB_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
SRC="$LIB_ROOT/src/org/pfe/devsecops/AcrPublisher.groovy"

# De la ligne du marqueur R58 jusqu'au dernier `exit 1` du bloc verdict.
# Groovy desechappe les chaines '''...''' avant que le shell ne les voie :
# `\\(` dans la source devient `\(` pour sh. Le test reproduit exactement cette
# etape, sinon il testerait un script que Jenkins n'execute jamais.
sed -n '/# R58 -- le code HTTP/,/^ *exit 1$/p' "$SRC" \
  | sed 's/^ *//' \
  | sed 's/\\\\/\\/g' > "$WORK/verdict.sh"

if [ ! -s "$WORK/verdict.sh" ]; then
    ko "R58 — la logique de verdict est introuvable dans AcrPublisher.groovy"
else
    ok "R58 — logique de verdict extraite de la source ($(wc -l < "$WORK/verdict.sh") lignes)"
fi

# Garde-fou : le test doit vraiment exercer la lecture du corps.
grep -q 'PROVENANCE_VERIFIED' "$WORK/verdict.sh" \
  && ok "R58 — le bloc extrait teste bien le statut du corps" \
  || ko "R58 — le bloc extrait ne lit pas le statut du corps"

run_verdict() {
    code="$1"; body="$2"
    printf '%s' "$body" > "$WORK/resp.json"
    {
      echo "set -u"
      echo "CODE='$code'"
      # le vrai script lit /tmp/acr_prov_resp.txt ; on le redirige vers le bac a sable
      sed "s#/tmp/acr_prov_resp.txt#$WORK/resp.json#g" "$WORK/verdict.sh"
    } > "$WORK/run.sh"
    ( cd "$WORK" && /bin/sh -e run.sh > vout.txt 2>&1 )
    echo $?
}

# ── Cas A : le corps EXACT du build #10 -> rattrapable (2), jamais 0 ──────
B10='{"status":"REJECTED","failureCode":"DEPLOY_COMMIT_MISSING","reason":"Identite de build non resolue pour ce projet/build - impossible de correler."}'
st=$(run_verdict 201 "$B10")
[ "$st" = "2" ] && ok "R58 CAS A — 201 + REJECTED/DEPLOY_COMMIT_MISSING -> 2 (rattrapable)" \
                || ko "R58 CAS A — attendu 2, obtenu $st"
[ "$st" != "0" ] && ok "R58 CAS A — le corps du build #10 n'est PLUS pris pour un succes" \
                 || ko "R58 CAS A — regression : 201 + REJECTED compte encore comme un succes"

# ── Cas B : enregistrement reellement verifie -> 0 ────────────────────────
st=$(run_verdict 201 '{"status":"PROVENANCE_VERIFIED","artifactId":"abc","digest":"sha256:'"$(printf '0%.0s' $(seq 1 64))"'"}')
[ "$st" = "0" ] && ok "R58 CAS B — PROVENANCE_VERIFIED -> 0 (seul vrai succes)" \
                || ko "R58 CAS B — attendu 0, obtenu $st"

# ── Cas C : le chemin MANUEL n'est pas acceptable depuis la CI ────────────
st=$(run_verdict 201 '{"status":"UNVERIFIED_MANUAL_ARTIFACT","artifactId":"abc"}')
[ "$st" = "1" ] && ok "R58 CAS C — UNVERIFIED_MANUAL_ARTIFACT refuse en CI (1, terminal)" \
                || ko "R58 CAS C — attendu 1, obtenu $st"

# ── Cas D : refus TERMINAL -> 1, pas 2 (ne pas consommer la fenetre) ──────
for fc in DEPLOY_ARTIFACT_COMMIT_MISMATCH DEPLOY_BUILD_MISMATCH \
          DEPLOY_ARTIFACT_REPOSITORY_MISMATCH DEPLOY_ARTIFACT_DIGEST_MISMATCH \
          DEPLOY_ARTIFACT_REVISION_MISSING DEPLOY_ENVIRONMENT_UNRESOLVED; do
    st=$(run_verdict 201 '{"status":"REJECTED","failureCode":"'"$fc"'"}')
    [ "$st" = "1" ] && ok "R58 CAS D — $fc -> 1 (terminal, pas de reprise)" \
                    || ko "R58 CAS D — $fc : attendu 1, obtenu $st"
done

# ── Cas E : echec de transport -> 1, et jamais 0 ──────────────────────────
for c in 000 401 403 500 502; do
    st=$(run_verdict "$c" '')
    [ "$st" = "1" ] && ok "R58 CAS E — HTTP $c -> 1" \
                    || ko "R58 CAS E — HTTP $c : attendu 1, obtenu $st"
done

# ── Cas F : corps vide/illisible sur un 2xx -> jamais un succes ───────────
for body in '' 'not json at all' '{"unexpected":"shape"}' '{"status":"WAT"}'; do
    st=$(run_verdict 201 "$body")
    [ "$st" != "0" ] && ok "R58 CAS F — 2xx + corps inexploitable ($(printf '%.20s' "${body:-<vide>}")) -> jamais 0" \
                     || ko "R58 CAS F — corps inexploitable pris pour un succes"
done

# ── Cas G : controle negatif — l'ancien motif acceptait bien le build #10 ─
printf '%s' "$B10" > "$WORK/resp.json"
cat > "$WORK/old.sh" <<'OLD'
set -u
CODE=201
case "$CODE" in 2*) exit 0 ;; *) exit 1 ;; esac
OLD
( cd "$WORK" && /bin/sh -e old.sh >/dev/null 2>&1 ); oldst=$?
[ "$oldst" = "0" ] && ok "R58 CAS G — controle : l'ancien motif acceptait bien ce refus (le bug etait reel)" \
                   || ko "R58 CAS G — controle invalide : l'ancien motif ne reproduit pas le bug"

echo "shell: $PASS reussis, $FAIL echoues"
[ "$FAIL" -eq 0 ] || exit 1
echo "ALL SHELL STATUS TESTS PASSED"
