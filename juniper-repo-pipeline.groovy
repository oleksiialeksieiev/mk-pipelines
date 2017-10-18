/**
 * Update packages in juniper repo
 *
 * Expected parameters:
 *   DIST             Ubuntu distribution name
 *   TARGET           repo readiness [testing/stable].
 *   PKG_URL          URL for contrail package
 *   JC_VERSION       
 *   REMOVE_PACKAGES  
 *
**/
def aptly = new com.mirantis.mk.Aptly()
def common = new com.mirantis.mk.Common()

def pkgname
def debFiles
APTLY_REPO="ubuntu-${DIST}-${JC_VERSION}"

node() {
    try {
        stage('Download paсkage') {
            sh "mkdir -p ${BUILD_NUMBER}";
            sh "wget --trust-server-names -P ${BUILD_NUMBER}/ \"${PKG_URL}\"";
            pkgname = sh(script: "basename ${BUILD_NUMBER}/*.deb", returnStdout: true).trim()
        }
        stage('Extract deb paсkage') {
            sh "dpkg-deb -x ${BUILD_NUMBER}/*.deb ${BUILD_NUMBER}/";
            sh "mkdir -p ${BUILD_NUMBER}/${JC_VERSION}";
            sh "tar xzf ${BUILD_NUMBER}/opt/contrail/contrail_packages/contrail_debs.tgz -C ${BUILD_NUMBER}/${JC_VERSION}";
        }
        lock("aptly-api") {
          stage("upload") {
            buildSteps = [:]
            debFiles = sh script: "basename -a ${BUILD_NUMBER}/${JC_VERSION}/*.deb", returnStdout: true
            for (file in debFiles.tokenize()) {
              workspace = common.getWorkspace()
              def fh = new File((workspace+"/${BUILD_NUMBER}/${JC_VERSION}/"+file).trim())
              buildSteps[fh.name.split('_')[0]] = aptly.uploadPackageStep(
                  "${BUILD_NUMBER}/${JC_VERSION}/"+fh.name,
                  APTLY_URL,
                  APTLY_REPO,
                  true
              )
            }
           parallel buildSteps
        }
          stage("publish") {
            aptly.snapshotRepo(APTLY_URL, APTLY_REPO, timestamp)
            aptly.publish(APTLY_URL)
          }
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
        throw e
    } finally {
        stage ('Clean workspace directories') {
        sh(returnStatus: true, script: "rm -rf ${BUILD_NUMBER}")
        }
    }
}
