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
                mklink "C:/Program Files/Git/bin/nohup.exe" "C:/Program Files/git/usr/bin/nohup.exe"
                mklink "C:/Program Files/Git/bin/msys-2.0.dll" "C:/Program Files/git/usr/bin/msys-2.0.dll"
                mklink "C:/Program Files/Git/bin/msys-iconv-2.dll" "C:/Program Files/git/usr/bin/msys-iconv-2.dll"
                mklink "C:/Program Files/Git/bin/msys-intl-8.dll" "C:/Program Files/git/usr/bin/msys-intl-8.dll"
                sh 'git submodule update --init --recursive'
                sh 'mvn clean package'
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
                sh 'mvn javadoc:jar source:jar deploy -DskipTests'
            }
        }
    }

    post {
        always {
            deleteDir()
        }
    }
}
