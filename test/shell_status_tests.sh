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
echo "shell: $PASS reussis, $FAIL echoues"
[ "$FAIL" -eq 0 ] || exit 1
echo "ALL SHELL STATUS TESTS PASSED"
