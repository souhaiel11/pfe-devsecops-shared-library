package org.pfe.devsecops

/**
 * PIPELINE_GENERIC scanner orchestration: SonarQube, Trivy, OWASP
 * Dependency-Check, Kubernetes target reachability, OWASP ZAP DAST.
 *
 * The developer never sees any of this. Every method here reproduces the
 * exact commands/flags/timeouts previously hand-maintained in the
 * pfe-app-test Jenkinsfile so behavior does not silently change during
 * migration -- only its location does.
 */
class ScannerRunner implements Serializable {

    private final def steps
    private final StageTelemetry telemetry

    ScannerRunner(steps, StageTelemetry telemetry) {
        this.steps = steps
        this.telemetry = telemetry
    }

    // ------------------------------------------------------------------
    // SonarQube
    // ------------------------------------------------------------------
    void runSonar(String appName, String workingDirectory, boolean isPullRequest,
                   String changeId, String changeBranch, String changeTarget) {
        telemetry.buildStageStatus['sonar'] = 'FAILED'
        steps.dir(workingDirectory ?: '.') {
            steps.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                steps.withSonarQubeEnv(PlatformConfig.SONAR_ENV_NAME) {
                    String prArgs = ''
                    if (isPullRequest) {
                        steps.echo "SonarQube: pull request mode #${changeId}"
                        prArgs = " -Dsonar.pullrequest.key=${changeId}" +
                                 " -Dsonar.pullrequest.branch=${changeBranch}" +
                                 " -Dsonar.pullrequest.base=${changeTarget}"
                    } else {
                        steps.echo 'SonarQube: standard branch mode'
                    }
                    steps.sh """
                        set -e
                        mvn sonar:sonar -B \
                          -DskipTests=true \
                          -Djacoco.skip=true \
                          -Dsonar.projectKey="${appName}" \
                          -Dsonar.projectName="${appName}" \
                          -Dsonar.host.url="${PlatformConfig.SONAR_HOST_URL}" \
                          -Dsonar.token="\$SONAR_TOKEN" \
                          ${prArgs} 2>&1 | tee sonar-analysis.log
                    """
                }
                telemetry.buildStageStatus['sonar'] = 'SUCCESS'
            }
            // ceTaskId: printed by the scanner on stdout ("More about the report
            // processing at .../api/ce/task?id=...") but never forwarded before --
            // WF1 could never correct the quality gate (SONAR_CE_TASK_ID_MISSING).
            // Best-effort extraction: never fails the stage if absent.
            String ceTaskLine = steps.sh(
                script: "grep -oE 'ce/task\\?id=[A-Za-z0-9_-]+' sonar-analysis.log 2>/dev/null | tail -1 || true",
                returnStdout: true
            ).trim()
            if (ceTaskLine) {
                telemetry.sonar.ceTaskId = ceTaskLine.replaceFirst(/^ce\/task\?id=/, '')
            }
        }
    }

    // ------------------------------------------------------------------
    // Trivy
    // ------------------------------------------------------------------
    void runTrivy(String imageName, String imageTag, String buildNumber, String reportBase) {
        steps.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
            steps.withEnv(["IMAGE_NAME=${imageName}", "IMAGE_TAG=${imageTag}", "REPORT_BASE=${reportBase}"]) {
                steps.sh '''
                    set -e
                    BUILD="''' + buildNumber + '''"
                    TMP_DIR="/tmp/trivy-test-$BUILD"

                    rm -rf "$TMP_DIR"
                    mkdir -p "$TMP_DIR" "$REPORT_BASE"

                    echo "=== Persistent Trivy cache ==="
                    docker volume create trivy-cache >/dev/null || true

                    echo "=== Exporting image ==="
                    docker save "$IMAGE_NAME:$IMAGE_TAG" -o "$TMP_DIR/image.tar"

                    echo "=== Updating Trivy DB ==="
                    docker run --rm -v trivy-cache:/root/.cache \
                      aquasec/trivy:latest image --download-db-only || true

                    echo "=== Trivy container ==="
                    TRIVY_CID=$(docker create -v trivy-cache:/root/.cache \
                      --entrypoint sh aquasec/trivy:latest -c "sleep 1800")
                    docker start "$TRIVY_CID" >/dev/null
                    docker cp "$TMP_DIR/image.tar" "$TRIVY_CID:/image.tar"

                    echo "=== Trivy scan ==="
                    docker exec "$TRIVY_CID" trivy image \
                      --input /image.tar \
                      --scanners vuln,misconfig \
                      --skip-db-update \
                      --skip-java-db-update \
                      --exit-code 0 \
                      --format json \
                      --severity CRITICAL,HIGH,MEDIUM \
                      --no-progress \
                      --timeout 30m \
                      --output /trivy-report.json || true

                    docker cp "$TRIVY_CID:/trivy-report.json" "$TMP_DIR/trivy-report.json" || true
                    docker rm -f "$TRIVY_CID" >/dev/null 2>&1 || true

                    if [ ! -s "$TMP_DIR/trivy-report.json" ]; then
                      echo '{"SchemaVersion":2,"Results":[],"status":"trivy_report_missing"}' > "$TMP_DIR/trivy-report.json"
                    fi

                    cp "$TMP_DIR/trivy-report.json" "$REPORT_BASE/trivy-report.json"
                    rm -rf "$TMP_DIR"

                    echo "=== Final Trivy report ==="
                    ls -lh "$REPORT_BASE/trivy-report.json" || true
                '''
            }
        }
    }

    // ------------------------------------------------------------------
    // OWASP Dependency-Check
    // ------------------------------------------------------------------
    void runOwasp(String reportBase, boolean jenkinsHardGate, String cvssFailThreshold, String workingDirectory) {
        steps.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
            String failCvss = jenkinsHardGate ? cvssFailThreshold : '11'
            steps.echo "OWASP failBuildOnCVSS = ${failCvss}"
            steps.dir(workingDirectory ?: '.') {
                steps.withEnv([
                    "REPORT_BASE=${reportBase}",
                    "ODC_VERSION=${PlatformConfig.OWASP_DC_VERSION}",
                    "ODC_DATA=${PlatformConfig.OWASP_DC_DATA_DIR}",
                    "FAIL_CVSS=${failCvss}"
                ]) {
                    // NVD_API_KEY is bound by the caller via withCredentials before this
                    // method runs -- referenced here only as an inherited shell env var,
                    // never a groovy-interpolated literal.
                    steps.sh '''
                        set -e
                        mkdir -p "$REPORT_BASE" "$ODC_DATA"

                        {
                          echo "=== Step 1: NVD update (with API key) ==="
                          timeout 20m mvn org.owasp:dependency-check-maven:$ODC_VERSION:update-only \
                            -DdataDirectory="$ODC_DATA" \
                            -DnvdApiKey="$NVD_API_KEY" \
                            -DnvdApiDelay=2000 \
                            -DnvdMaxRetryCount=15 \
                            -DnvdValidForHours=168 \
                            -DretireJsAnalyzerEnabled=false \
                            -DnodeAuditAnalyzerEnabled=false \
                            -DossindexAnalyzerEnabled=false \
                            -B || true

                          echo "=== Step 2: dependency scan (local cache) ==="
                          timeout 20m mvn org.owasp:dependency-check-maven:$ODC_VERSION:check \
                            -Dformat=ALL \
                            -DfailBuildOnCVSS="$FAIL_CVSS" \
                            -DfailOnError=false \
                            -DdataDirectory="$ODC_DATA" \
                            -DautoUpdate=false \
                            -DretireJsAnalyzerEnabled=false \
                            -DnodeAuditAnalyzerEnabled=false \
                            -DossindexAnalyzerEnabled=false \
                            -B || true
                        } > "$REPORT_BASE/owasp.log" 2>&1

                        echo "=== End of OWASP log ==="
                        tail -80 "$REPORT_BASE/owasp.log" || true

                        if [ -f target/dependency-check-report.json ]; then
                          cp target/dependency-check-report.json "$REPORT_BASE/dependency-check-report.json"
                        else
                          echo '{"dependencies":[],"status":"owasp_report_missing"}' > "$REPORT_BASE/dependency-check-report.json"
                        fi
                        [ -f target/dependency-check-report.html ] && cp target/dependency-check-report.html "$REPORT_BASE/" || true
                        [ -f target/dependency-check-report.xml ]  && cp target/dependency-check-report.xml  "$REPORT_BASE/" || true

                        echo "=== Final OWASP reports ==="
                        ls -lh "$REPORT_BASE"/dependency-check-report.* || true
                    '''
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Kubernetes target reachability (informational, never blocking)
    // ------------------------------------------------------------------
    void checkKubernetesTarget(String k8sNamespace, String kubeconfigPath, String zapTargetServiceName) {
        steps.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
            steps.withEnv(["KUBECONFIG=${kubeconfigPath}", "K8S_NAMESPACE=${k8sNamespace}", "ZAP_TARGET_SVC=${zapTargetServiceName}"]) {
                steps.sh '''
                    set +e
                    echo "=== kubectl (client) ==="
                    kubectl version --client
                    echo "=== Services ==="
                    kubectl get svc  -n "$K8S_NAMESPACE"
                    echo "=== Pods ==="
                    kubectl get pods -n "$K8S_NAMESPACE"
                    echo "=== Target service ==="
                    kubectl get svc "$ZAP_TARGET_SVC" -n "$K8S_NAMESPACE"
                    true
                '''
            }
        }
    }

    // ------------------------------------------------------------------
    // OWASP ZAP DAST
    // ------------------------------------------------------------------
    void runZap(String k8sNamespace, String kubeconfigPath, String zapTargetUrl, String buildNumber, String reportBase) {
        steps.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
            String zapPod = "zap-scan-${buildNumber}"
            steps.withEnv([
                "KUBECONFIG=${kubeconfigPath}", "K8S_NAMESPACE=${k8sNamespace}",
                "REPORT_BASE=${reportBase}", "ZAP_POD=${zapPod}",
                "ZAP_IMAGE=${PlatformConfig.ZAP_IMAGE}", "ZAP_TARGET_URL=${zapTargetUrl}"
            ]) {
                // Verbatim port of the pfe-app-test in-pod probe (spider + active scan +
                // JSON/HTML report), unchanged except the target URL now comes from the
                // ZAP_TARGET_URL env var instead of being hardcoded in the script text.
                steps.sh '''
                    set +e
                    mkdir -p "$REPORT_BASE"

                    echo "=== Kubernetes access ==="
                    if ! kubectl get svc -n "$K8S_NAMESPACE" >/dev/null 2>&1; then
                      echo '{"site":[],"status":"zap_k8s_unreachable"}' > "$REPORT_BASE/zap-report.json"
                      exit 0
                    fi

                    echo "=== Cleaning up previous pod ==="
                    kubectl delete pod "$ZAP_POD" -n "$K8S_NAMESPACE" --ignore-not-found=true || true

                    echo "=== Launching ZAP pod (spider then active scan) ==="
                    kubectl run "$ZAP_POD" \
                      -n "$K8S_NAMESPACE" \
                      --image="$ZAP_IMAGE" \
                      --image-pull-policy=IfNotPresent \
                      --restart=Never \
                      --requests='memory=768Mi,cpu=500m' \
                      --limits='memory=1536Mi,cpu=1' \
                      --env="ZAP_TARGET_URL=$ZAP_TARGET_URL" \
                      --command -- sh -lc '
                        mkdir -p /zap/wrk && cd /zap/wrk

                        /zap/zap.sh -daemon -host 0.0.0.0 -port 8090 \
                          -config api.disablekey=true \
                          -config api.addrs.addr.name=.* \
                          -config api.addrs.addr.regex=true \
                          -config database.recoverylog=false \
                          > /zap/wrk/zap.log 2>&1 &

                        python3 - <<PY
import urllib.request, urllib.parse, time, json, sys, os

base   = "http://127.0.0.1:8090"
target = os.environ["ZAP_TARGET_URL"]

def call(path, params=None, timeout=30):
    url = base + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    return urllib.request.urlopen(url, timeout=timeout).read().decode()

for _ in range(240):
    try:
        call("/JSON/core/view/version/", timeout=3); print("ZAP_READY"); break
    except Exception:
        time.sleep(3)
else:
    print("ZAP_NOT_READY_AFTER_720S")
    try:
        print("ZAP_LOG_TAIL:")
        print(open("/zap/wrk/zap.log").read()[-2000:])
    except Exception as e2:
        print("COULD_NOT_READ_LOG", e2)
    sys.exit(1)

try:
    call("/JSON/core/action/accessUrl/", {"url": target, "followRedirects": "true"})
    print("TARGET_ACCESSED")
except Exception as e:
    print("TARGET_ACCESS_ERROR", e)

try:
    sid = json.loads(call("/JSON/spider/action/scan/", {"url": target, "recurse": "true"}))["scan"]
    while True:
        st = json.loads(call("/JSON/spider/view/status/", {"scanId": sid}))["status"]
        if int(st) >= 100: break
        time.sleep(3)
    print("SPIDER_DONE")
except Exception as e:
    print("SPIDER_ERROR", e)

try:
    aid = json.loads(call("/JSON/ascan/action/scan/", {"url": target, "recurse": "true"}))["scan"]
    while True:
        st = json.loads(call("/JSON/ascan/view/status/", {"scanId": aid}))["status"]
        if int(st) >= 100: break
        time.sleep(5)
    print("ACTIVE_SCAN_DONE")
except Exception as e:
    print("ACTIVE_SCAN_ERROR", e)

try:
    open("/zap/wrk/zap-report.json","w").write(call("/OTHER/core/other/jsonreport/", timeout=60))
    print("JSON_REPORT_CREATED")
except Exception as e:
    print("JSON_REPORT_ERROR", e)
    open("/zap/wrk/zap-report.json","w").write(json.dumps({"site":[{"name":target,"alerts":[]}],"status":"zap_report_api_fallback"}))
try:
    open("/zap/wrk/zap-report.html","w").write(call("/OTHER/core/other/htmlreport/", timeout=60))
    print("HTML_REPORT_CREATED")
except Exception as e:
    print("HTML_REPORT_ERROR", e)
PY

                        ls -lh /zap/wrk || true
                        touch /zap/wrk/zap.done
                        sleep 3600
                      '

                    echo "=== Waiting for ZAP report (up to 36 min) ==="
                    for i in $(seq 1 220); do
                      if kubectl exec "$ZAP_POD" -n "$K8S_NAMESPACE" -- test -f /zap/wrk/zap.done 2>/dev/null; then
                        echo "ZAP done"; break
                      fi
                      echo "Waiting for ZAP... $i"; sleep 10
                    done

                    echo "=== ZAP logs ==="
                    kubectl logs "$ZAP_POD" -n "$K8S_NAMESPACE" || true

                    echo "=== Retrieving reports ==="
                    kubectl cp "$K8S_NAMESPACE/$ZAP_POD:/zap/wrk/zap-report.json" "$REPORT_BASE/zap-report.json" || true
                    kubectl cp "$K8S_NAMESPACE/$ZAP_POD:/zap/wrk/zap-report.html" "$REPORT_BASE/zap-report.html" || true
                    kubectl cp "$K8S_NAMESPACE/$ZAP_POD:/zap/wrk/zap.log"        "$REPORT_BASE/zap.log"        || true

                    if [ ! -s "$REPORT_BASE/zap-report.json" ]; then
                      echo '{"site":[],"status":"zap_report_missing"}' > "$REPORT_BASE/zap-report.json"
                    fi

                    echo "=== Cleaning up ZAP pod ==="
                    kubectl delete pod "$ZAP_POD" -n "$K8S_NAMESPACE" --ignore-not-found=true || true
                    true
                '''
            }
        }
    }
}
