pipeline {
    agent any
    environment {
        AWS_REGION = 'us-east-1'
        S3_BUCKET_NAME = 'todo-list-aws-bucket-43-unique'
        STAGE = 'Prod'
        BASE_URL = ''
        PIP_BIN_PATH = '/var/lib/jenkins/.local/bin'
        DYNAMODB_TABLE = 'ToDoList'
    }
    stages {
        stage('Get Code') {
            steps {
                echo 'Clonando el repositorio...'
                git branch: 'develop', url: 'https://github.com/FrankyGA/PracticaToDoList-CP1D.git'
                sh 'whoami'
                sh 'hostname'
                
                // Descargar el fichero samconfig.toml
                script {
                    // Diferenciar entre la rama develop o master para descargar una configuración u otra
                    def configBranch = env.BRANCH_NAME == 'develop' ? 'production' : 'staging'
                    echo "Descargando el archivo samconfig.toml desde la rama: ${configBranch}"
                    sh """
                        if [ -d "config-repo" ]; then
                            rm -rf config-repo
                        fi
                        git clone -b ${configBranch} https://github.com/FrankyGA/todo-list-aws-config.git config-repo
                        cp config-repo/samconfig.toml .
                    """
                }
            }
        }

        stage('Show Config File') {
            steps {
                echo 'Mostrando el contenido del archivo samconfig.toml...'
                sh 'cat samconfig.toml'
            }
        }

        stage('Finish') {
            steps {
                echo '¡Ejecución de la pipeline finalizada!'
                sh 'whoami'
                sh 'hostname'
            }
        }
    }
}