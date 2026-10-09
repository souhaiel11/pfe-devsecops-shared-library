/**
 * R50 — tests hors ligne de la publication ACR et de l'enregistrement de
 * provenance.
 *
 * Tout est simule : aucun identifiant reel, aucun `docker push`, aucun appel
 * Azure, aucune ressource modifiee. FakeSteps enregistre les scripts shell
 * qu'AcrPublisher AURAIT executes, et les tests verifient leur contrat.
 *
 *   test/run-offline-tests.sh /chemin/groovy-all.jar
 */
import org.pfe.devsecops.AcrPublisher
import org.pfe.devsecops.StageTelemetry

int failures = 0
def check = { boolean cond, String label ->
    if (cond) { println "PASS: ${label}" } else { println "FAIL: ${label}"; failures++ }
}

String SHA = 'a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0'
String OTHER_SHA = '0000111122223333444455556666777788889999'
String DIGEST = 'sha256:' + ('ab12cd34' * 8)

Map projectWithAcr = [
    id         : '078b5e76-c6f3-4014-9f52-ea5e4f435399',
    azureConfig: [registry: 'myregistry', imageRepository: 'my-app']
]

/** Steps dont le shell repond selon un fragment du script. */
def stepsFor = { Map answers ->
    def s = new FakeSteps()
    s.stdoutDecider = { String script ->
        for (e in answers) { if (script.contains(e.key)) return e.value }
        return null
    }
    return s
}

// ════════════════════════════════════════════════════════════════════════════
// 1. TAG UNIQUE — build + commit, jamais `latest`, jamais un numero nu
// ════════════════════════════════════════════════════════════════════════════
check(AcrPublisher.publishTag('8', SHA) == '8-a1b2c3d4e5f6',
    'TAG — le tag combine le numero de build et le prefixe du commit')
check(AcrPublisher.publishTag('8', SHA) != 'latest' && AcrPublisher.publishTag('8', SHA) != '8',
    'TAG — ni `latest` ni le numero de build seul')
check(AcrPublisher.publishTag('8', SHA) != AcrPublisher.publishTag('8', OTHER_SHA),
    'TAG — deux commits differents au meme build donnent deux tags differents')
check(AcrPublisher.publishTag('9', SHA) != AcrPublisher.publishTag('8', SHA),
    'TAG — deux builds du meme commit donnent deux tags differents')
check(AcrPublisher.publishTag('8', SHA) ==~ /[A-Za-z0-9_][A-Za-z0-9._-]{0,127}/,
    'TAG — respecte le motif de tag du DTO backend')
check(AcrPublisher.publishTag(null, SHA) == null && AcrPublisher.publishTag('', SHA) == null,
    'TAG — pas de numero de build, pas de tag')
check(AcrPublisher.publishTag('8', 'court') == null && AcrPublisher.publishTag('8', null) == null,
    'TAG — un commit non complet ne produit aucun tag')
check(AcrPublisher.publishTag('8; rm -rf /', SHA) == null,
    'TAG — un numero de build non numerique est refuse, pas assaini')

// ════════════════════════════════════════════════════════════════════════════
// 2. CIBLE ACR — jamais choisie a la place du projet
// ════════════════════════════════════════════════════════════════════════════
def t = AcrPublisher.resolveTarget(projectWithAcr)
check(t.ok && t.registry == 'myregistry' && t.repository == 'my-app',
    'CIBLE — registre et depot viennent de la configuration du projet')
check(AcrPublisher.resolveTarget([id: projectWithAcr.id]).reason == 'ACR_NOT_CONFIGURED',
    'CIBLE — aucun azureConfig => ACR_NOT_CONFIGURED, aucun registre devine')
check(AcrPublisher.resolveTarget([id: projectWithAcr.id, azureConfig: [registry: 'r']]).reason == 'ACR_NOT_CONFIGURED',
    'CIBLE — un depot manquant n\'est jamais remplace par le nom du projet')
check(AcrPublisher.resolveTarget([id: projectWithAcr.id, azureConfig: [imageRepository: 'a']]).reason == 'ACR_NOT_CONFIGURED',
    'CIBLE — un registre manquant n\'est jamais remplace par un defaut')
check(AcrPublisher.resolveTarget(null).reason == 'PROJECT_RECORD_UNAVAILABLE',
    'CIBLE — projet introuvable => motif explicite')
check(AcrPublisher.resolveTarget([id: 'pas-un-uuid', azureConfig: projectWithAcr.azureConfig]).reason == 'PROJECT_ID_UNAVAILABLE',
    'CIBLE — un identifiant de projet invalide est refuse')
check(AcrPublisher.resolveTarget([id: projectWithAcr.id,
        azureConfig: [registry: 'ok', imageRepository: 'MAJUSCULES']]).reason == 'ACR_REPOSITORY_INVALID',
    'CIBLE — un depot hors motif ACR est refuse, pas normalise en silence')
check(AcrPublisher.resolveTarget([id: projectWithAcr.id,
        azureConfig: [registry: 'bad;reg', imageRepository: 'ok']]).reason == 'ACR_REGISTRY_INVALID',
    'CIBLE — un registre hors motif est refuse')

// ════════════════════════════════════════════════════════════════════════════
// 3. IDENTITE DE L'IMAGE — l'image de CETTE execution, pas une ancienne
// ════════════════════════════════════════════════════════════════════════════
def okSteps = stepsFor(['image inspect': SHA + '\n'])
def pub = new AcrPublisher(okSteps, new StageTelemetry())
check(pub.verifyLocalImage('my-app:8', SHA).ok,
    'IDENTITE — le label de revision egal au commit du run est accepte')
String inspectScript = okSteps.shScripts.find { it.contains('image inspect') }
check(inspectScript != null && inspectScript.contains('docker image inspect "$PFE_LOCAL_REF"'),
    'IDENTITE — l\'image est adressee par sa reference exacte')
check(!okSteps.shScripts.any { it.contains('docker images') },
    'IDENTITE — aucun parcours de `docker images`')
check(!okSteps.shScripts.any { it.contains(':latest') },
    'IDENTITE — `latest` n\'est jamais utilise pour identifier l\'artefact')

def staleSteps = stepsFor(['image inspect': OTHER_SHA + '\n'])
def staleRes = new AcrPublisher(staleSteps, new StageTelemetry()).verifyLocalImage('my-app:8', SHA)
check(!staleRes.ok && staleRes.reason == 'LOCAL_IMAGE_REVISION_MISMATCH',
    'IDENTITE — une image locale d\'un autre commit est refusee (cas de l\'image perimee)')
def noLabel = stepsFor(['image inspect': '\n'])
check(new AcrPublisher(noLabel, new StageTelemetry()).verifyLocalImage('my-app:8', SHA).reason
        == 'LOCAL_IMAGE_REVISION_LABEL_MISSING',
    'IDENTITE — une image sans label de revision est refusee')
check(new AcrPublisher(new FakeSteps(), new StageTelemetry()).verifyLocalImage('my-app:8', 'court').reason
        == 'LOCAL_IMAGE_IDENTITY_UNVERIFIABLE',
    'IDENTITE — sans commit prouvable, rien n\'est verifiable')

// ════════════════════════════════════════════════════════════════════════════
// 4. PORTEE DES IDENTIFIANTS (R51)
// Le defaut corrige : lies globalement, leur absence cassait TOUT projet, y
// compris ceux sans ACR qui n'ont jamais demande a publier.
// ════════════════════════════════════════════════════════════════════════════
String ACR_ID = 'ACR_CREDENTIALS'
String SECRET_ID = 'N8N_INTERNAL_SECRET'

/** Steps repondant par fragment de script, avec identifiants manquants au choix. */
def stepsWith = { Map answers, List missing ->
    def s = new FakeSteps()
    s.missingCredentialIds = (missing ?: []) as Set
    s.stdoutDecider = { String script ->
        for (e in answers) { if (script.contains(e.key)) return e.value }
        return null
    }
    return s
}
String projectJson = groovy.json.JsonOutput.toJson([projectWithAcr])
String noAcrJson = groovy.json.JsonOutput.toJson([[id: projectWithAcr.id]])

// ── CAS 1 : projet SANS registre + identifiants ABSENTS ───────────────────
// Doit construire normalement et NE PAS echouer a cause de la publication.
def c1 = stepsWith(['internal/by-job': noAcrJson], [ACR_ID])
def tel1 = new StageTelemetry(); tel1.checkoutFullSha = SHA
def res1 = null
boolean threw1 = false
try { res1 = new AcrPublisher(c1, tel1).publishImage(jobName: 'x', imageName: 'x', imageTag: '3') }
catch (err) { threw1 = true }
check(!threw1, 'CAS 1 — un projet sans registre ne doit PAS echouer a cause de la publication')
check(tel1.docker.push_status == 'NOT_CONFIGURED', 'CAS 1 — push_status = NOT_CONFIGURED')
check(res1?.published == false, 'CAS 1 — aucune publication revendiquee')
check(!c1.requestedCredentialIds.contains(ACR_ID),
    'CAS 1 — l\'identifiant ACR n\'est JAMAIS demande pour un projet sans registre')
check(!c1.shScripts.any { it.contains('docker push') }, 'CAS 1 — aucun push tente')
check(!c1.shScripts.any { it.contains('docker login') }, 'CAS 1 — aucun login registre')
check(!c1.shScripts.any { it.contains('artifacts/provenance') },
    'CAS 1 — aucun appel de provenance')
check(!c1.shScripts.any { it.contains(' az ') || it.contains('az acr') },
    'CAS 1 — aucun appel Azure CLI')

// ── CAS 1b : meme chose avec le SECRET INTERNE absent ─────────────────────
// La lecture de config est le seul appel qui en a besoin : sans lui on ne peut
// pas savoir si un registre existe, donc on degrade honnetement.
def c1b = stepsWith([:], [SECRET_ID, ACR_ID])
def tel1b = new StageTelemetry(); tel1b.checkoutFullSha = SHA
boolean threw1b = false
try { new AcrPublisher(c1b, tel1b).publishImage(jobName: 'x', imageName: 'x', imageTag: '3') }
catch (err) { threw1b = true }
check(!threw1b, 'CAS 1b — un secret interne absent ne casse pas un projet non publiant')
check(tel1b.docker.push_status == 'NOT_CONFIGURED', 'CAS 1b — NOT_CONFIGURED, pas FAILED')
check(!c1b.requestedCredentialIds.contains(ACR_ID),
    'CAS 1b — l\'identifiant ACR n\'est pas demande non plus')
check(!c1b.shScripts.any { it.contains('internal/by-job') },
    'CAS 1b — la lecture de config n\'est meme pas tentee sans son secret')

// ── CAS 2 : projet AVEC registre + identifiants presents ──────────────────
def c2 = stepsWith(['internal/by-job': projectJson, 'image inspect': SHA + '\n'], [])
def tel2 = new StageTelemetry(); tel2.checkoutFullSha = SHA
def res2 = new AcrPublisher(c2, tel2).publishImage(jobName: 'my-app', imageName: 'my-app', imageTag: '8')
check(res2?.published == true, 'CAS 2 — le chemin de publication est bien active')
check(tel2.docker.push_status == 'SUCCESS', 'CAS 2 — push_status = SUCCESS')
check(c2.requestedCredentialIds.contains(SECRET_ID) && c2.requestedCredentialIds.contains(ACR_ID),
    'CAS 2 — les deux identifiants sont demandes, dans ce chemin seulement')
check(c2.shScripts.any { it.contains('docker push') }, 'CAS 2 — le push est bien emis')
check(tel2.docker.published_reference == 'myregistry.azurecr.io/my-app:8-a1b2c3d4e5f6',
    'CAS 2 — reference publiee complete et unique')
check(tel2.docker.provenance_status == 'PENDING',
    'CAS 2 — provenance differee : annoncee PENDING, jamais un faux succes')
check(tel2.docker.digest == null,
    'CAS 2 — aucun digest revendique par le pipeline : le backend en est l\'autorite')
check(!c2.shScripts.any { it.contains('artifacts/provenance') },
    'CAS 2 — la provenance n\'est PAS enregistree dans cette etape')

// ── CAS 3 : publication EXIGEE + identifiants manquants => echec dur ──────
def c3 = stepsWith(['internal/by-job': projectJson, 'image inspect': SHA + '\n'], [ACR_ID])
def tel3 = new StageTelemetry(); tel3.checkoutFullSha = SHA
boolean threw3 = false; String msg3 = ''
try {
    new AcrPublisher(c3, tel3).publishImage(jobName: 'my-app', imageName: 'my-app',
        imageTag: '8', requirePublish: true)
} catch (err) { threw3 = true; msg3 = err.message }
check(threw3 && msg3.contains('ACR_PUBLISH_REQUIRED_BUT_UNAVAILABLE'),
    'CAS 3 — publication exigee sans identifiant ACR => echec explicite')
check(msg3.contains('ACR_CREDENTIALS_UNAVAILABLE'), 'CAS 3 — le motif nomme l\'identifiant manquant')
check(tel3.docker.push_status == 'FAILED', 'CAS 3 — FAILED, pas NOT_CONFIGURED')

// ── CAS 3b : publication exigee sans registre configure ───────────────────
def c3b = stepsWith(['internal/by-job': noAcrJson], [])
def tel3b = new StageTelemetry(); tel3b.checkoutFullSha = SHA
boolean threw3b = false; String msg3b = ''
try { new AcrPublisher(c3b, tel3b).publishImage(jobName: 'x', imageName: 'x', imageTag: '3', requirePublish: true) }
catch (err) { threw3b = true; msg3b = err.message }
check(threw3b && msg3b.contains('ACR_NOT_CONFIGURED'),
    'CAS 3b — publication exigee sans registre => echec nommant la cause')
check(msg3b.contains("nothing is ever chosen on the project's behalf"),
    'CAS 3b — le message refuse explicitement de choisir a la place du projet')

// ── Une erreur qui n'est PAS un identifiant manquant doit propager ────────
def realFailure = new FakeSteps()
realFailure.stdoutDecider = { String s -> s.contains('internal/by-job') ? projectJson : (s.contains('image inspect') ? SHA + '\n' : null) }
realFailure.statusDecider = { String s -> s.contains('docker push') ? 1 : 0 }
def telRF = new StageTelemetry(); telRF.checkoutFullSha = SHA
boolean threwRF = false; String msgRF = ''
try { new AcrPublisher(realFailure, telRF).publishImage(jobName: 'my-app', imageName: 'my-app', imageTag: '8') }
catch (err) { threwRF = true; msgRF = err.message }
check(threwRF && msgRF.contains('ACR_PUSH_FAILED'),
    'ECHEC REEL — un push qui echoue fait echouer le build, meme sans requirePublish')
check(telRF.docker.push_status == 'FAILED', 'ECHEC REEL — push_status = FAILED')
check(telRF.docker.provenance_status != 'SUCCESS',
    'ECHEC REEL — la provenance n\'est jamais faussement reussie apres un push echoue')

// ════════════════════════════════════════════════════════════════════════════
// 5. PUSH — docker login seul, aucun Azure CLI (mode BACKEND_AGENT)
// ════════════════════════════════════════════════════════════════════════════
String pushScript = c2.shScripts.find { it.contains('docker push') }
check(pushScript.contains('docker tag "$PFE_LOCAL_REF" "$PFE_REMOTE_REF"'),
    'PUSH — c\'est l\'image locale verifiee qui est retaguee puis poussee')
check(!pushScript.contains(':latest'), 'PUSH — rien n\'est pousse sous `latest`')
check(!pushScript.contains('az '), 'PUSH — aucun appel Azure CLI : Jenkins n\'en a pas')
check(pushScript.contains('--password-stdin') && pushScript.contains('$ACR_PASSWORD'),
    'PUSH — le mot de passe passe par stdin depuis l\'environnement')
check(!pushScript.contains('motdepasse') && !pushScript.contains('secret-value'),
    'PUSH — aucune valeur de secret dans le texte du script')
check(pushScript.contains('docker logout'), 'PUSH — la session registre est refermee')

// Aucun script de la classe ne doit invoquer az.
String allScripts = c2.shScripts.join('\n')
check(!(allScripts =~ /(^|\s)az\s/), 'MODE — aucun script du publisher n\'appelle az')

// ════════════════════════════════════════════════════════════════════════════
// 6. PROVENANCE — apres ingestion, contrat existant, digest non revendique
// ════════════════════════════════════════════════════════════════════════════
def pv = stepsWith([:], [])
int code = new AcrPublisher(pv, new StageTelemetry())
        .registerProvenance(t, 8, SHA, '8-a1b2c3d4e5f6')
check(code == 0, 'PROVENANCE — un 2xx simule est un succes')
def sent = new groovy.json.JsonSlurperClassic().parseText(pv.writtenFiles['acr-provenance-request.json'])
check(sent.projectId == projectWithAcr.id && sent.buildNumber == 8 && sent.commitSha == SHA
        && sent.repository == 'my-app' && sent.tag == '8-a1b2c3d4e5f6',
    'PROVENANCE — projet, build, commit COMPLET, depot et tag sont transmis')
check(sent.commitSha.length() == 40,
    'PROVENANCE — le commit autoritaire est le sha COMPLET, pas le prefixe du tag')
check(!sent.containsKey('digest'),
    'PROVENANCE — aucun digest revendique : le backend le resout et en est l\'autorite')
check(!sent.containsKey('registry'), 'PROVENANCE — aucun champ registry (absent du DTO)')
check(!sent.containsKey('provenanceVerified') && !sent.containsKey('deployed'),
    'PROVENANCE — aucun resultat ni etat de deploiement en entree')
String provScript = pv.shScripts.find { it.contains('artifacts/provenance') }
check(provScript.contains('/api/azure-deploy/artifacts/provenance'),
    'PROVENANCE — endpoint CI existant, aucun nouveau')
check(provScript.contains('$N8N_INTERNAL_SECRET'), 'PROVENANCE — secret depuis l\'environnement')
check(provScript.contains('case "$CODE" in 2*) exit 0 ;; *) exit 1 ;; esac'),
    'PROVENANCE — un code non-2xx propage un echec')

// ── recordProvenance : reessaie pendant que la plateforme ingere ──────────
def retry = stepsWith([:], [])
int calls = 0
retry.statusDecider = { String s -> s.contains('artifacts/provenance') ? (++calls >= 3 ? 0 : 1) : 0 }
def telR = new StageTelemetry(); telR.checkoutFullSha = SHA
telR.docker.push_status = 'SUCCESS'
telR.docker.published_reference = 'myregistry.azurecr.io/my-app:8-a1b2c3d4e5f6'
new AcrPublisher(retry, telR).recordProvenance(
    [published: true, target: t, tag: '8-a1b2c3d4e5f6', commitSha: SHA, buildNumber: '8'], 5, 1)
check(telR.docker.provenance_status == 'SUCCESS',
    'INGESTION — la provenance reussit apres que la plateforme a ingere le build')
check(calls == 3, 'INGESTION — les tentatives sont bornees et reellement reessayees')
check(telR.docker.push_status == 'SUCCESS', 'INGESTION — le push reste SUCCESS')

// ── push SUCCESS + provenance definitivement refusee ─────────────────────
def provFail = stepsWith([:], [])
provFail.statusDecider = { String s -> s.contains('artifacts/provenance') ? 1 : 0 }
def telPF = new StageTelemetry(); telPF.checkoutFullSha = SHA
telPF.docker.push_status = 'SUCCESS'
telPF.docker.published_tag = '8-a1b2c3d4e5f6'
boolean threwPF = false; String msgPF = ''
try {
    new AcrPublisher(provFail, telPF).recordProvenance(
        [published: true, target: t, tag: '8-a1b2c3d4e5f6', commitSha: SHA, buildNumber: '8'], 2, 1)
} catch (err) { threwPF = true; msgPF = err.message }
check(threwPF && msgPF.contains('ACR_PROVENANCE_REGISTRATION_FAILED'),
    'PUSH OK / PROVENANCE KO — le build echoue')
check(telPF.docker.push_status == 'SUCCESS',
    'PUSH OK / PROVENANCE KO — le fait historique du push n\'est PAS reecrit')
check(telPF.docker.provenance_status == 'FAILED',
    'PUSH OK / PROVENANCE KO — provenance = FAILED, deux faits distincts')
check(telPF.docker.published_tag == '8-a1b2c3d4e5f6',
    'PUSH OK / PROVENANCE KO — les coordonnees publiees restent vraies')

// ── rien n'est enregistre si rien n'a ete publie ─────────────────────────
def noPub = stepsWith([:], [])
new AcrPublisher(noPub, new StageTelemetry()).recordProvenance([published: false], 3, 1)
check(noPub.shScripts.isEmpty(), 'NON PUBLIE — aucune provenance n\'est tentee')

// ── secret interne absent au moment de l'enregistrement ──────────────────
def noSecret = stepsWith([:], [SECRET_ID])
def telNS = new StageTelemetry(); telNS.docker.push_status = 'SUCCESS'
boolean threwNS = false; String msgNS = ''
try {
    new AcrPublisher(noSecret, telNS).recordProvenance(
        [published: true, target: t, tag: '8-a1b2c3d4e5f6', commitSha: SHA, buildNumber: '8'], 2, 1)
} catch (err) { threwNS = true; msgNS = err.message }
check(threwNS && msgNS.contains('ACR_PROVENANCE_SECRET_UNAVAILABLE'),
    'PROVENANCE — un artefact publie non enregistrable fait echouer le build')
check(telNS.docker.push_status == 'SUCCESS', 'PROVENANCE — le push reste SUCCESS')

// ════════════════════════════════════════════════════════════════════════════
// 7. AUCUNE SEMANTIQUE DE DEPLOIEMENT
// ════════════════════════════════════════════════════════════════════════════
check(!tel2.docker.containsKey('deployed') && !tel2.docker.containsKey('deployment'),
    'DEPLOIEMENT — aucun champ de deploiement n\'est cree')
check(!tel2.docker.values().any { String.valueOf(it).toUpperCase().contains('DEPLOY') },
    'DEPLOIEMENT — aucune valeur ne dit « deploye »')
String src7 = new File("${System.getenv('LIB_ROOT') ?: '.'}/src/org/pfe/devsecops/AcrPublisher.groovy").text
// L'assertion porte sur les ECRITURES, pas sur le mot : le publisher dit
// explicitement « Published is not deployed », ce qui est exactement le
// comportement voulu et ne doit pas faire echouer le test.
check(!(src7 =~ /telemetry\.docker\.(deployed|deployment|deploy_status|authorized|healthy)/),
    'DEPLOIEMENT — le publisher n\'ecrit aucun champ de deploiement dans la telemetrie')
// On exclut les commentaires ET les lignes d'echo : le publisher ECRIT
// « Published is not deployed: ... » a la console, ce qui est precisement le
// comportement voulu. Seules les lignes de CODE sont examinees.
String code7 = src7.split('\n').findAll {
    String l = it.trim()
    !l.startsWith('*') && !l.startsWith('//') && !l.startsWith('/*') && !l.contains('steps.echo')
}.join('\n')
check(!(code7 =~ /(deployed|authorized|healthy) *:/),
    'DEPLOIEMENT — aucune cle de deploiement dans un payload emis')
check(!src7.contains('deployment_attempts') && !src7.contains('/deploy'),
    'DEPLOIEMENT — aucun appel de deploiement n\'est emis')

// ════════════════════════════════════════════════════════════════════════════
// 8. GENERICITE — aucune identite de projet dans le code
// ════════════════════════════════════════════════════════════════════════════
String body8 = src7.split('\n').findAll { !it.trim().startsWith('*') && !it.trim().startsWith('//') }.join('\n')
['pfe-app-test', 'app-test-pfe-vermeg', 'vuln-testapp', 'acrpfedevsecops', 'devsecops-testbed'].each { needle ->
    check(!body8.contains(needle), "GENERICITE — AcrPublisher ne nomme pas ${needle}")
}
check(!(body8 =~ /["'][0-9a-fA-F]{8}-[0-9a-fA-F]{4}-/), 'GENERICITE — aucun UUID de projet en dur')
check(!body8.contains('rg-pfe') && !body8.contains('francecentral'),
    'GENERICITE — aucune ressource Azure nommee en dur')

// ════════════════════════════════════════════════════════════════════════════
// 9. LE PIPELINE NE LIE PLUS CES IDENTIFIANTS GLOBALEMENT
// ════════════════════════════════════════════════════════════════════════════
String pipeline = new File("${System.getenv('LIB_ROOT') ?: '.'}/vars/devSecOpsPipeline.groovy").text
int credBlockEnd = pipeline.indexOf(']) {', pipeline.indexOf('withCredentials(['))
String globalBlock = pipeline.substring(pipeline.indexOf('withCredentials(['), credBlockEnd)
check(!globalBlock.contains('CRED_ACR'),
    'PORTEE — l\'identifiant ACR n\'est plus lie dans le bloc global')
check(!globalBlock.contains('CRED_INTERNAL_SECRET'),
    'PORTEE — le secret interne n\'est plus lie dans le bloc global')
check(globalBlock.contains('CRED_SONAR_TOKEN') && globalBlock.contains('CRED_NVD_API_KEY'),
    'PORTEE — les identifiants reellement globaux restent lies')
check(pipeline.contains('acrPublisher.publishImage('), 'PORTEE — l\'etape de publication appelle publishImage')
check(pipeline.contains('acrPublisher.recordProvenance('), 'PORTEE — la provenance a sa propre etape')
int sendIdx = pipeline.indexOf('reporter.send(')
int provIdx = pipeline.indexOf('acrPublisher.recordProvenance(')
check(sendIdx > 0 && provIdx > sendIdx,
    'SEQUENCE — la provenance est enregistree APRES l\'envoi du rapport (le backend a besoin de l\'incident)')
int publishIdx = pipeline.indexOf('acrPublisher.publishImage(')
check(publishIdx > 0 && publishIdx < sendIdx,
    'SEQUENCE — le push a lieu avant le rapport, pour que ses faits y figurent')

println ''
if (failures > 0) {
    println "ACR PUBLISHER TESTS: ${failures} echec(s)"
    System.exit(1)
}
println 'ALL ACR PUBLISHER TESTS PASSED'
