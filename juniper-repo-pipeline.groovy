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
def timestamp = common.getDatetime()

def pkgname
def debFiles
def pkgFiles
APTLY_REPO="ubuntu-${DIST}-${JC_VERSION}"


def removePackage(server, repo, packagelist) {
    pkgFiles = sh script: "curl -s -f -X GET -H 'Content-Type: application/json'  ${server}/api/repos/${repo}/packages", returnStdout: true
    for (file in pkgFiles.tokenize(",")) {
        for (rmpkg in packagelist.tokenize(",")){
            if (file.contains(" "+rmpkg+" ")){
               pkgrtorem = file
               echo("Package to item to remove: ${pkgrtorem}")
               sh("curl -X DELETE -H 'Content-Type: application/json' --data '{\"PackageRefs\": [${pkgrtorem}]}'  ${server}/api/repos/${repo}/packages")
            }
        }
    }
}


node('slave01') {
    try {
        stage('Download package') {
            sh "mkdir -p ${BUILD_NUMBER}";
            sh "wget --trust-server-names -P ${BUILD_NUMBER}/ \"${PKG_URL}\"";
            pkgname = sh(script: "basename ${BUILD_NUMBER}/*.deb", returnStdout: true).trim()
        }
        stage('Extract deb package') {
            sh "dpkg-deb -x ${BUILD_NUMBER}/*.deb ${BUILD_NUMBER}/";
            sh "mkdir -p ${BUILD_NUMBER}/${JC_VERSION}";
            sh "tar xzf ${BUILD_NUMBER}/opt/contrail/contrail_packages/contrail_debs.tgz -C ${BUILD_NUMBER}/${JC_VERSION}";
        }
        lock("aptly-api") {
          stage("upload") {
            workspace = common.getWorkspace()
            buildSteps = [:]
            debFiles = sh script: "basename -a ${BUILD_NUMBER}/${JC_VERSION}/*.deb", returnStdout: true
            for (file in debFiles.tokenize()) {
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
        stage  ("remove extra packages"){
          removePackage(APTLY_URL, APTLY_REPO, REMOVE_PACKAGES)
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
