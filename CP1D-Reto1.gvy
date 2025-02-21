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
            }
        }

        stage('Install Dependencies') {
            steps {
                echo 'Instalando dependencias de Python...'
                sh 'pip install flake8 bandit coverage pytest boto3 moto==4.0.0 pyOpenSSL==23.2.0'
            }
        }

        stage('Static Test') {
            steps {
                echo 'Ejecutando pruebas estáticas...'
                sh 'export PATH=$PATH:${PIP_BIN_PATH} && flake8 src/ --output-file=flake8-report.txt'
                sh 'export PATH=$PATH:${PIP_BIN_PATH} && bandit -r src/ -f html -o bandit-report.html'
                publishHTML([
                    allowMissing: false, 
                    alwaysLinkToLastBuild: true, 
                    keepAll: true, 
                    reportName: 'Bandit Report', 
                    reportDir: '.', 
                    reportFiles: 'bandit-report.html'
                ])
                archiveArtifacts artifacts: 'flake8-report.txt', allowEmptyArchive: true
            }
        }

        stage('Unit Test') {
            steps {
                echo 'Ejecutando pruebas unitarias...'
                sh 'export PYTHONPATH=$PYTHONPATH:$WORKSPACE/src && export PATH=$PATH:${PIP_BIN_PATH} && pytest test/unit/TestToDo.py'
            }
        }

        stage('SAM') {
            steps {
                echo 'Construyendo, validando y desplegando la aplicación SAM...'
                sh 'sam build --region ${AWS_REGION}'
                sh 'sam validate --region ${AWS_REGION}'

                sh '''
                    aws cloudformation delete-stack --stack-name todo-list-stack --region ${AWS_REGION} || true
                    aws cloudformation wait stack-delete-complete --stack-name todo-list-stack --region ${AWS_REGION} || true
                '''

                sh '''
                    if aws s3api head-bucket --bucket ${S3_BUCKET_NAME} 2>/dev/null; then
                        aws s3 rb s3://${S3_BUCKET_NAME} --force
                    fi
                '''

                sh '''
                    aws s3api create-bucket --bucket ${S3_BUCKET_NAME} --region ${AWS_REGION} || true  
                    sam deploy --no-confirm-changeset --stack-name todo-list-stack --capabilities CAPABILITY_IAM --region ${AWS_REGION} --s3-bucket ${S3_BUCKET_NAME} --no-fail-on-empty-changeset --parameter-overrides Stage=${STAGE}
                '''

                script {
                    def tableName = "${STAGE}-TodosDynamoDbTable"
                    def describeTable = sh(script: "aws dynamodb describe-table --table-name ${tableName} --region ${AWS_REGION}", returnStatus: true, returnStdout: true)
                    if (describeTable == 0) {
                        echo "La tabla DynamoDB ${tableName} ha sido creada exitosamente."
                    } else {
                        error "La tabla DynamoDB ${tableName} no fue encontrada."
                    }
                }
            }
        }

        stage('Obtener ID de la API Gateway') {
            steps {
                script {
                    echo "Obteniendo ID de la API Gateway..."
                    def apiId = sh(script: "aws apigateway get-rest-apis --query \"items[?name=='todo-list-stack'].id\" --output text --region ${AWS_REGION}", returnStdout: true).trim()
                    echo "API ID: ${apiId}"
                    
                    if (apiId) {
                        BASE_URL = "https://${apiId}.execute-api.${AWS_REGION}.amazonaws.com/${STAGE}"
                        echo "API URL: ${BASE_URL}"
                    } else {
                        error "No se encontró la API con el nombre todo-list-stack."
                    }
                }
            }
        }

        stage('REST API Integration Tests') {
            steps {
                script {
                    echo 'Ejecutando pruebas de integración de la API REST usando curl...'
                    def apiUrl = BASE_URL

                    def postResponseCode = sh(script: """curl -s -X POST "${apiUrl}/todos" \
                        -H "Content-Type: application/json" \
                        -d '{"text": "Test task", "userId": "12345"}' \
                        -w "%{http_code}" -o response.json""", returnStdout: true).trim()

                    echo "POST Response Code: ${postResponseCode}"
                    if (postResponseCode != '200') {
                        error "POST request failed! Expected 200, got ${postResponseCode}"
                    }

                    def responseBody = readFile('response.json')
                    echo "POST Response Body: ${responseBody}"

                    def TASK_ID = sh(script: "jq -r '.body' response.json | jq -r '.id'", returnStdout: true).trim()
                    echo "Task created with ID: ${TASK_ID}"

                    def putResponseCode = sh(script: """curl -s -X PUT "${apiUrl}/todos/${TASK_ID}" \
                        -H "Content-Type: application/json" \
                        -d '{"text": "Updated task", "checked": true}' \
                        -w "%{http_code}" -o put_response.json""", returnStdout: true).trim()

                    echo "PUT Response Code: ${putResponseCode}"
                    if (putResponseCode != '200') {
                        error "PUT request failed! Expected 200, got ${putResponseCode}"
                    }

                    def getTaskResponseCode = sh(script: """curl -s -X GET "${apiUrl}/todos/${TASK_ID}" \
                        -w "%{http_code}" -o get_response.json""", returnStdout: true).trim()

                    echo "GET Task Response Code: ${getTaskResponseCode}"
                    if (getTaskResponseCode != '200') {
                        error "GET request failed! Expected 200, got ${getTaskResponseCode}"
                    }

                    def taskDetails = readFile('get_response.json')
                    echo "Task Details: ${taskDetails}"

                    def deleteResponseCode = sh(script: """curl -s -X DELETE "${apiUrl}/todos/${TASK_ID}" \
                        -H "Content-Type: application/json" \
                        -w "%{http_code}" -o delete_response.json""", returnStdout: true).trim()

                    echo "DELETE Response Code: ${deleteResponseCode}"
                    if (deleteResponseCode != '200') {
                        error "DELETE request failed! Expected 200, got ${deleteResponseCode}"
                    }
                }
            }
        }

        stage('Promote') {
            steps {
                script {
                    echo 'Promoviendo la versión a producción...'
                    withCredentials([usernamePassword(credentialsId: 'github-credentials', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')]) {
                        sh '''
                            git config --global user.name "${GIT_USERNAME}"
                            git config --global user.password "${GIT_PASSWORD}"
                            git checkout master
                            git merge develop
                            git push https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/FrankyGA/PracticaToDoList-CP1D.git master
                        '''
                    }
                }
            }
        }

        stage('Declarative: Post Actions') {
            steps {
                echo '¡Ejecución de la pipeline finalizada!'
            }
        }
    }
}
