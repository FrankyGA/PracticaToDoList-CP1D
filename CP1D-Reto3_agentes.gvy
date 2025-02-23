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
            agent { label 'principal' }
            steps {
                echo 'Clonando el repositorio...'
                git branch: 'develop', url: 'https://github.com/FrankyGA/PracticaToDoList-CP1D.git'
                sh 'whoami'
                sh 'hostname'
            }
        }
        stage('Static Test') {
            agent { label 'agent1' }
            steps {
                echo 'Ejecutando pruebas estáticas...'
                sh 'export PATH=$PATH:${PIP_BIN_PATH} && flake8 src/ --output-file=flake8-report.txt'
                sh 'export PATH=$PATH:${PIP_BIN_PATH} && bandit -r src/ -f html -o bandit-report.html'
                publishHTML([allowMissing: false, alwaysLinkToLastBuild: true, keepAll: true, reportName: 'Bandit Report', reportDir: '.', reportFiles: 'bandit-report.html'])
                archiveArtifacts artifacts: 'flake8-report.txt', allowEmptyArchive: true
                sh 'whoami'
                sh 'hostname'
            }
        }

        stage('Unit Test') {
            agent { label 'principal' }
            steps {
                echo 'Ejecutando pruebas unitarias...'
                sh 'export PYTHONPATH=$PYTHONPATH:$WORKSPACE/src && export PATH=$PATH:${PIP_BIN_PATH} && pytest test/unit/TestToDo.py'
                sh 'whoami'
                sh 'hostname'
            }
        }
        stage('Obtener ID de la API Gateway') {
            agent { label 'principal' }
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
                    sh 'whoami'
                    sh 'hostname'
                }
            }
        }
        stage('REST API Integration Tests') {
            agent { label 'agent2' }
            steps {
                script {
                    echo 'Ejecutando pruebas de integración de la API REST usando curl...'
                    def apiUrl = BASE_URL
                    // POST request a /todos
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
                    // Extraer el ID de la tarea creada
                    def TASK_ID = sh(script: "jq -r '.body' response.json | jq -r '.id'", returnStdout: true).trim()
                    echo "Task created with ID: ${TASK_ID}"
                    // PUT request a /todos/{id} (actualización de tarea)
                    def putResponseCode = sh(script: """curl -s -X PUT "${apiUrl}/todos/${TASK_ID}" \
                        -H "Content-Type: application/json" \
                        -d '{"text": "Updated task", "checked": true}' \
                        -w "%{http_code}" -o put_response.json""", returnStdout: true).trim()
                    echo "PUT Response Code: ${putResponseCode}"
                    if (putResponseCode != '200') {
                        error "PUT request failed! Expected 200, got ${putResponseCode}"
                    }
                    // GET request a /todos/{id} (obtener la tarea actualizada)
                    def getTaskResponseCode = sh(script: """curl -s -X GET "${apiUrl}/todos/${TASK_ID}" \
                        -w "%{http_code}" -o get_response.json""", returnStdout: true).trim()
                    echo "GET Task Response Code: ${getTaskResponseCode}"
                    if (getTaskResponseCode != '200') {
                        error "GET request failed! Expected 200, got ${getTaskResponseCode}"
                    }
                    def taskDetails = readFile('get_response.json')
                    echo "Task Details: ${taskDetails}"

                    // DELETE request a /todos/{id} (eliminar la tarea)
                    def deleteResponseCode = sh(script: """curl -s -X DELETE "${apiUrl}/todos/${TASK_ID}" \
                        -H "Content-Type: application/json" \
                        -w "%{http_code}" -o delete_response.json""", returnStdout: true).trim()
                    echo "DELETE Response Code: ${deleteResponseCode}"
                    if (deleteResponseCode != '200') {
                        error "DELETE request failed! Expected 200, got ${deleteResponseCode}"
                    }
                    sh 'whoami'
                    sh 'hostname'
                }
            }
        }
        stage('Declarative: Post Actions') {
            agent { label 'principal' }
            steps {
                echo '¡Ejecución de la pipeline finalizada!'
                sh 'whoami'
                sh 'hostname'
            }
        }
    }
}