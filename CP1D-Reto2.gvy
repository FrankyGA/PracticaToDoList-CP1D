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
                git branch: 'master', url: 'https://github.com/FrankyGA/PracticaToDoList-CP1D.git'
            }
        }
        
        stage('Deploy') {
            steps {
                echo 'Desplegando la aplicación en el entorno de producción...'
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
                    sam deploy --no-confirm-changeset --stack-name todo-list-stack --capabilities CAPABILITY_IAM --region ${AWS_REGION} --s3-bucket ${S3_BUCKET_NAME} --no-fail-on-empty-changeset --parameter-overrides Stage=production
                '''

                script {
                    def tableName = "production-TodosDynamoDbTable"
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

        stage('Rest Test') {
            steps {
                script {
                    echo 'Ejecutando pruebas de solo lectura en la API REST...'
                    def apiUrl = BASE_URL

                    def getResponseCode = sh(script: """curl -s -X GET "${apiUrl}/todos" \
                        -w "%{http_code}" -o get_response.json""", returnStdout: true).trim()

                    echo "GET Response Code: ${getResponseCode}"
                    if (getResponseCode != '200') {
                        error "GET request failed! Expected 200, got ${getResponseCode}"
                    }

                    def responseBody = readFile('get_response.json')
                    echo "GET Response Body: ${responseBody}"
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