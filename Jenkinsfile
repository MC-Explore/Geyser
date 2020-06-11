pipeline {
    agent any
    tools {
        maven 'Maven 3'
        jdk 'Java 8'
    }
    options {
        buildDiscarder(logRotator(artifactNumToKeepStr: '5'))
    }
    stages {
        stage ('Build') {
            steps {
                run 'git submodule update --init --recursive'
                run 'mvn clean package'
            }
            post {
                success {
                    archiveArtifacts artifacts: 'bootstrap/**/target/*.jar', excludes: 'bootstrap/**/target/original-*.jar', fingerprint: true
                }
            }
        }

        stage ('Deploy') {
            when {
                branch "master"
            }
            steps {
                run 'mvn javadoc:jar source:jar deploy -DskipTests'
            }
        }
    }

    post {
        always {
            deleteDir()
        }
    }
}
