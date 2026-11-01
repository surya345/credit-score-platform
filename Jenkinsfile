pipeline {
  agent any
  tools { jdk 'jdk-25'; maven 'maven-3.9' }
  stages {
    stage('Backend and integration') { steps { sh 'mvn -B -ntp -Pintegration verify' } }
    stage('Frontend') { steps { dir('frontend') { sh 'npm ci --no-audit --no-fund && npm run build' } } }
    stage('Complete Docker stack') { steps { sh 'python3 scripts/verify-compose.py' } }
  }
  post {
    always {
      junit '**/target/surefire-reports/TEST-*.xml, **/target/failsafe-reports/TEST-*.xml'
      archiveArtifacts artifacts: 'integration-tests/target/platform-logs/**/*.log, integration-tests/target/compose/**/compose.log', allowEmptyArchive: true
    }
  }
}
