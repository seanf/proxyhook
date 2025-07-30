#!/usr/bin/env groovy


/**
 * Jenkinsfile for proxyhook server/client
 *
 * Environment variables:
 *
 * PROXYHOOK_DOCKER_REGISTRY - Docker registry host to use with OpenShift platform
 * PROXYHOOK_OPENSHIFT - OpenShift platform URL
 * PROXYHOOK_SERVER_PROJECT - OpenShift project name for DEV/QA/STAGE/PROD deployments
 *
 * For pull requests:
 * - build and test
 * - optional: wait for input
 * - deploy to DEV
 * - while (input = redeploy) deploy to DEV
 * - wait for input
 * - deploy to QA
 * - while (input = redeploy) deploy to QA
 *
 * For master:
 * - build and test
 * - deploy to STAGE
 * - wait for input
 * - deploy to PROD
 */

@Field
public static final String PROJ_BASE = 'github.com/zanata/proxyhook'

// Import pipeline library for utility methods & classes:
// ansicolor(), Notifier, PullRequests, Strings
@Field
public static final String PIPELINE_LIBRARY_BRANCH = 'v0.3.0'

// GROOVY-3278:
//   Using referenced String constant as value of Annotation causes compile error
@Library('zanata-pipeline-library@v0.3.0')
import org.zanata.jenkins.Notifier
import org.zanata.jenkins.PullRequests
import org.zanata.jenkins.ScmGit
import static org.zanata.jenkins.Reporting.codecov
import static org.zanata.jenkins.StackTraces.getStackTrace

import groovy.transform.Field

// The first milestone step starts tracking concurrent build order
milestone()

PullRequests.ensureJobDescription(env, manager, steps)

@Field
def pipelineLibraryScmGit

@Field
def mainScmGit

@Field
def notify
// initialiser must be run separately (bindings not available during compilation phase)

/* Only keep the 10 most recent builds. */
def projectProperties = [
  [
    $class: 'GithubProjectProperty',
    projectUrlStr: "https://$PROJ_BASE"
  ],
  [
    $class: 'BuildDiscarderProperty',
    strategy: [$class: 'LogRotator', numToKeepStr: '10']
  ],
]
properties(projectProperties)


// Decide whether the build should be tagged and deployed. If the result $tag
// is non-null, a corresponding git tag $tag has been created. If the build
// succeeds, we should push $tag and deploy the application. If the result
// is null, this is not the master branch, and we should not perform any
// release/deploy steps.
String makeTag() {
  if (env.BRANCH_NAME == 'master') {
    sh './gradlew setVersionFromBuild'
    def ver = readProperties(file: 'gradle.properties')?.version
    if (ver == null) return null
    def tag = 'v' + ver
    sh "git commit gradle.properties -m 'Update version for $ver' && git tag $tag"
    return tag
  } else {
    return null
  }
}

// use timestamps for Jenkins logs
timestamps {
  // allocate a node for build+unit tests
  node() {
    echo "running on node ${env.NODE_NAME}"
    pipelineLibraryScmGit = new ScmGit(env, steps, 'https://github.com/zanata/zanata-pipeline-library')
    pipelineLibraryScmGit.init(PIPELINE_LIBRARY_BRANCH)
    mainScmGit = new ScmGit(env, steps, "https://$PROJ_BASE")
    mainScmGit.init(env.BRANCH_NAME)
    notify = new Notifier(env, steps, currentBuild,
        pipelineLibraryScmGit, mainScmGit, (env.GITHUB_COMMIT_CONTEXT) ?: 'Jenkinsfile',
    )
    // generate logs in colour
    ansicolor {
      try {
        stage('Checkout') {
          // notify methods send instant messages about the build progress
          notify.started()

          // Shallow Clone does not work with RHEL7, which uses git-1.8.3
          // https://issues.jenkins-ci.org/browse/JENKINS-37229
          checkout scm

          // Clean the workspace
          sh "git clean -fdx"
        }
        def tag = null
        stage('Build') {
          notify.startBuilding()
          tag = makeTag()
          buildAndTest()
        }
        stage('Deploy') {
          if (tag && isBuildResultSuccess()) {
          withCredentials(
            [[$class          : 'UsernamePasswordMultiBinding', credentialsId: 'zanata-jenkins',
              usernameVariable: 'GIT_USERNAME', passwordVariable: 'GITHUB_OAUTH2_TOKEN']]) {
            sh "git push https://$GIT_USERNAME:$GITHUB_OAUTH2_TOKEN@$PROJ_BASE $tag"
            // When https://issues.jenkins-ci.org/browse/JENKINS-28335 is done, use GitPublisher instead
          }
            // TODO deploy binaries, docker images
/*
              // TODO deploy client
              if (env.PROXYHOOK_CLIENT_HOME && env.PROXYHOOK_SERVER) {
                // deploy new version of client
                sh "cp client/client*-fat.jar ${env.PROXYHOOK_CLIENT_HOME}/proxyhook-client-fat.jar"
                sh "cp client/init.groovy.d/proxyhook_client.groovy $JENKINS_HOME/init.groovy.d/proxyhook_client.groovy"
                // restart the client
                sh "$JENKINS_HOME/init.groovy.d/proxyhook_client.groovy"
              }

              // TODO deploy server
              sh "./gradlew :server:tarball -x clean -x shadowJar"
              if (env.PROXYHOOK_NAMESPACE && env.PROXYHOOK_APP) {
                // deploy new version of server
                withCredentials([usernamePassword(
                    credentialsId: 'openshift',
                    usernameVariable: 'OPENSHIFT_LOGIN',
                    passwordVariable: 'OPENSHIFT_PASSWORD')]) {
                  docker.image('bigm/rhc').inside("-v /root/.openshift -v /private") {
                    sh """rhc setup --rhlogin ${env.OPENSHIFT_LOGIN}
                      --password ${env.OPENSHIFT_PASSWORD}"""
                    sh """rhc deploy --app ${env.PROXYHOOK_APP}
                        --namespace ${env.PROXYHOOK_NAMESPACE}
                        server/build/server-deploy*.tar.gz"""
                  }
                }
              }
            }
*/
          }
        }
        // Reduce workspace size
        sh "git clean -fdx"
        notify.successful()
      } catch (e) {
        notify.failed()
        currentBuild.result = 'FAILURE'
        throw e
      }
    }
  }
}

private void buildAndTest() {
// TODO run detekt
  sh """./gradlew clean build shadowJar jacocoTestReport
  """

  // archive build artifacts
  archive "**/build/libs/*.jar"

  // gather surefire results; mark build as unstable in case of failures
  junit(testResults: '**/build/test-results/**/*.xml')
  notify.testResults("UNIT", currentBuild.result)

  if (isBuildResultSuccess()) {
    // parse Jacoco test coverage
    step([$class: 'JacocoPublisher'])

    if (env.BRANCH_NAME == 'master') {
      step([$class: 'MasterCoverageAction'])
    } else if (env.BRANCH_NAME.startsWith('PR-')) {
      step([$class: 'CompareCoverageAction'])
    }

    // send test coverage data to codecov.io
    codecov(env, steps, mainScmGit)
  }
}

private boolean isBuildResultSuccess() {
  currentBuild.result in ['SUCCESS', null]
}
