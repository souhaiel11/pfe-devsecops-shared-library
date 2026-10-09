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
// 4. PUSH + DIGEST — le digest vient du REGISTRE, pas du demon local
// ════════════════════════════════════════════════════════════════════════════
def pushSteps = stepsFor(['az acr repository show': DIGEST + '\n'])
String resolved = new AcrPublisher(pushSteps, new StageTelemetry())
        .pushAndResolveDigest(t, 'my-app:8', '8-a1b2c3d4e5f6')
check(resolved == DIGEST, 'PUSH — le digest retourne par ACR est conserve tel quel')
String pushScript = pushSteps.shScripts.find { it.contains('docker push') }
check(pushScript.contains('az acr repository show'),
    'PUSH — le digest est demande au registre par la MEME commande que l\'agent, pas au demon local')
check(!pushScript.contains('RepoDigests'),
    'PUSH — le digest local n\'est pas la source du digest publie')
check(pushScript.contains('docker tag "$PFE_LOCAL_REF" "$PFE_REMOTE_REF"'),
    'PUSH — c\'est bien l\'image locale verifiee qui est retaguee puis poussee')
check(!pushScript.contains(':latest'), 'PUSH — rien n\'est pousse sous `latest`')
// Aucun secret dans le TEXTE du script : seules des references d'environnement.
check(pushScript.contains('$ACR_PASSWORD') && pushScript.contains('--password-stdin'),
    'PUSH — le mot de passe passe par stdin depuis l\'environnement')
check(!pushScript.contains('motdepasse') && !pushScript.contains('secret-value'),
    'PUSH — aucune valeur de secret n\'apparait dans le script')

def badDigest = stepsFor(['az acr repository show': 'pas-un-digest\n'])
check(new AcrPublisher(badDigest, new StageTelemetry())
        .pushAndResolveDigest(t, 'my-app:8', '8-a1b2c3d4e5f6') == null,
    'PUSH — une reponse qui n\'est pas un sha256 n\'est jamais acceptee')

// ════════════════════════════════════════════════════════════════════════════
// 5. PROVENANCE — contrat backend existant, sans champ invente
// ════════════════════════════════════════════════════════════════════════════
def provSteps = new FakeSteps()
int code = new AcrPublisher(provSteps, new StageTelemetry())
        .registerProvenance(t, 8, SHA, '8-a1b2c3d4e5f6', DIGEST)
check(code == 0, 'PROVENANCE — un code HTTP 2xx simule est un succes')
String body = provSteps.writtenFiles['acr-provenance-request.json']
check(body != null, 'PROVENANCE — la requete est ecrite puis envoyee par fichier')
def sent = new groovy.json.JsonSlurperClassic().parseText(body)
check(sent.projectId == projectWithAcr.id && sent.buildNumber == 8 && sent.commitSha == SHA
        && sent.repository == 'my-app' && sent.tag == '8-a1b2c3d4e5f6' && sent.digest == DIGEST,
    'PROVENANCE — projet, build, commit, depot, tag et digest sont transmis')
check(!sent.containsKey('registry'),
    'PROVENANCE — aucun champ `registry` : le DTO n\'en a pas, le backend le lit du projet')
check(!sent.containsKey('provenanceVerified') && !sent.containsKey('deployed'),
    'PROVENANCE — un resultat ne se declare pas en entree, et rien n\'est declare deploye')
String provScript = provSteps.shScripts.find { it.contains('artifacts/provenance') }
check(provScript.contains('/api/azure-deploy/artifacts/provenance'),
    'PROVENANCE — l\'endpoint CI existant est utilise, aucun nouveau')
check(provScript.contains('$N8N_INTERNAL_SECRET'),
    'PROVENANCE — authentifie par le secret interne existant, depuis l\'environnement')
check(provScript.contains('case "$CODE" in 2*) exit 0 ;; *) exit 1 ;; esac'),
    'PROVENANCE — un code non-2xx propage un echec')

def failProv = new FakeSteps()
failProv.statusDecider = { String s -> s.contains('artifacts/provenance') ? 1 : 0 }
check(new AcrPublisher(failProv, new StageTelemetry())
        .registerProvenance(t, 8, SHA, '8-a1b2c3d4e5f6', DIGEST) == 1,
    'PROVENANCE — un refus du backend est remonte comme echec')

// ════════════════════════════════════════════════════════════════════════════
// 6. BOUT EN BOUT — succes, et chaque mode d'echec
// ════════════════════════════════════════════════════════════════════════════
def fullSteps = stepsFor([
    'internal/by-job'      : groovy.json.JsonOutput.toJson([projectWithAcr]),
    'image inspect'        : SHA + '\n',
    'az acr repository show' : DIGEST + '\n'
])
def tel = new StageTelemetry()
tel.checkoutFullSha = SHA
tel.docker.build_status = 'SUCCESS'
new AcrPublisher(fullSteps, tel).publish(jobName: 'my-app', imageName: 'my-app', imageTag: '8')
check(tel.docker.push_status == 'SUCCESS' && tel.docker.provenance_status == 'SUCCESS',
    'E2E — publication et provenance reussies sont rapportees separement')
check(tel.docker.digest == DIGEST && tel.docker.published_tag == '8-a1b2c3d4e5f6'
        && tel.docker.registry == 'myregistry' && tel.docker.repository == 'my-app',
    'E2E — les coordonnees publiees sont celles confirmees par le registre')
check(tel.docker.published_reference == 'myregistry.azurecr.io/my-app:8-a1b2c3d4e5f6',
    'E2E — la reference publiee est complete et unique')
check(!tel.docker.containsKey('deployed') && !tel.docker.values().any { it == 'DEPLOYED' },
    'E2E — rien n\'est jamais declare deploye : publier n\'est pas deployer')

// Projet sans ACR, publication non exigee -> etat honnete, build non casse
def noAcrSteps = stepsFor([
    'internal/by-job': groovy.json.JsonOutput.toJson([[id: projectWithAcr.id]]),
    'image inspect'  : SHA + '\n'
])
def tel2 = new StageTelemetry(); tel2.checkoutFullSha = SHA
new AcrPublisher(noAcrSteps, tel2).publish(jobName: 'x', imageName: 'x', imageTag: '3')
check(tel2.docker.push_status == 'NOT_CONFIGURED',
    'E2E — un projet sans ACR est NOT_CONFIGURED, jamais un faux succes')
check(!noAcrSteps.shScripts.any { it.contains('docker push') },
    'E2E — aucun push n\'est tente sans cible configuree')

// Meme projet, mais publication EXIGEE -> echec explicite
def tel3 = new StageTelemetry(); tel3.checkoutFullSha = SHA
boolean raised = false
String msg = ''
try {
    new AcrPublisher(stepsFor(['internal/by-job': groovy.json.JsonOutput.toJson([[id: projectWithAcr.id]])]), tel3)
        .publish(jobName: 'x', imageName: 'x', imageTag: '3', requirePublish: true)
} catch (err) { raised = true; msg = err.message }
check(raised && msg.contains('ACR_PUBLISH_TARGET_UNRESOLVED'),
    'E2E — publication exigee sans ACR configure => echec explicite')
check(msg.contains('no registry is ever chosen on its behalf')
        || msg.contains('Configure azureConfig.registry'),
    'E2E — le message dit quoi configurer, au lieu de choisir un registre')
check(tel3.docker.push_status == 'FAILED', 'E2E — l\'echec est rapporte dans la telemetrie')

// Image perimee -> refus avant tout push
def tel4 = new StageTelemetry(); tel4.checkoutFullSha = SHA
def staleFull = stepsFor([
    'internal/by-job': groovy.json.JsonOutput.toJson([projectWithAcr]),
    'image inspect'  : OTHER_SHA + '\n'
])
raised = false
try { new AcrPublisher(staleFull, tel4).publish(jobName: 'my-app', imageName: 'my-app', imageTag: '8') }
catch (err) { raised = true; msg = err.message }
check(raised && msg.contains('ACR_PUBLISH_IMAGE_IDENTITY_REFUSED'),
    'E2E — une image qui n\'est pas celle de ce run fait echouer le build')
check(!staleFull.shScripts.any { it.contains('docker push') },
    'E2E — rien n\'est pousse quand l\'identite est refusee')

// Digest non resolu -> pas de provenance enregistree
def tel5 = new StageTelemetry(); tel5.checkoutFullSha = SHA
def noDigest = stepsFor([
    'internal/by-job'      : groovy.json.JsonOutput.toJson([projectWithAcr]),
    'image inspect'        : SHA + '\n',
    'az acr repository show' : '\n'
])
raised = false
try { new AcrPublisher(noDigest, tel5).publish(jobName: 'my-app', imageName: 'my-app', imageTag: '8') }
catch (err) { raised = true; msg = err.message }
check(raised && msg.contains('ACR_DIGEST_UNRESOLVED'),
    'E2E — un digest non resolu fait echouer le build')
check(!noDigest.shScripts.any { it.contains('artifacts/provenance') },
    'E2E — aucune provenance enregistree pour un artefact non adressable')

// Push reussi mais provenance refusee -> build en echec, push reste vrai
def tel6 = new StageTelemetry(); tel6.checkoutFullSha = SHA
def provFail = stepsFor([
    'internal/by-job'      : groovy.json.JsonOutput.toJson([projectWithAcr]),
    'image inspect'        : SHA + '\n',
    'az acr repository show' : DIGEST + '\n'
])
provFail.statusDecider = { String s -> s.contains('artifacts/provenance') ? 1 : 0 }
raised = false
try { new AcrPublisher(provFail, tel6).publish(jobName: 'my-app', imageName: 'my-app', imageTag: '8') }
catch (err) { raised = true; msg = err.message }
check(raised && msg.contains('ACR_PROVENANCE_REGISTRATION_FAILED'),
    'E2E — un enregistrement de provenance refuse fait echouer le build')
check(tel6.docker.push_status == 'SUCCESS' && tel6.docker.provenance_status == 'FAILED',
    'E2E — un push reellement effectue reste vrai, meme si la provenance echoue')

// Commit non prouvable -> rien n'est tente
def tel7 = new StageTelemetry()   // checkoutFullSha reste null
def noSha = new FakeSteps()
raised = false
try { new AcrPublisher(noSha, tel7).publish(jobName: 'x', imageName: 'x', imageTag: '1') }
catch (err) { raised = true; msg = err.message }
check(raised && msg.contains('ACR_PUBLISH_REVISION_UNAVAILABLE'),
    'E2E — sans commit prouvable, la publication est refusee avant tout appel')
check(noSha.shScripts.isEmpty(), 'E2E — aucun appel shell n\'est emis dans ce cas')

// ════════════════════════════════════════════════════════════════════════════
// 7. GENERICITE — aucune identite de projet dans le code
// ════════════════════════════════════════════════════════════════════════════
String libRoot = System.getenv('LIB_ROOT') ?: '.'
String source = new File("${libRoot}/src/org/pfe/devsecops/AcrPublisher.groovy").text
String body2 = source.split('\n').findAll { !it.trim().startsWith('*') && !it.trim().startsWith('//') }.join('\n')
['pfe-app-test', 'app-test-pfe-vermeg', 'vuln-testapp', 'acrpfedevsecops', 'devsecops-testbed'].each { needle ->
    check(!body2.contains(needle), "GENERICITE — AcrPublisher ne nomme pas ${needle}")
}
check(!(body2 =~ /["'][0-9a-fA-F]{8}-[0-9a-fA-F]{4}-/),
    'GENERICITE — aucun UUID de projet code en dur')
check(!body2.contains('rg-pfe') && !body2.contains('francecentral'),
    'GENERICITE — aucune ressource Azure nommee en dur')

println ''
if (failures > 0) {
    println "ACR PUBLISHER TESTS: ${failures} echec(s)"
    System.exit(1)
}
println 'ALL ACR PUBLISHER TESTS PASSED'
