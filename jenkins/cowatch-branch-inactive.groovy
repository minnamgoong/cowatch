/**
 * CoWatch Branch Inactive Marker
 * main 브랜치 머지 완료 시 해당 기능 브랜치에 비활성 태그를 생성합니다.
 */

node {
    def gitlabUrl = "${GITLAB_URL}"
    def gitlabTokenId = "${GITLAB_CREDENTIAL_ID}"
    def projectId = params.PROJECT_ID
    def sourceBranch = params.SOURCE_BRANCH
    def inactiveTagPrefix = params.INACTIVE_TAG_PREFIX

    stage('Create Inactive Tag') {
        echo "Marking branch ${sourceBranch} as INACTIVE in project ${projectId}..."
        
        withCredentials([string(credentialsId: gitlabTokenId, variable: 'GITLAB_TOKEN')]) {
            def tagName = "${inactiveTagPrefix}/${sourceBranch}"
            
            def response = httpRequest(
                url: "${gitlabUrl}/api/v4/projects/${projectId}/repository/tags",
                method: 'POST',
                contentType: 'APPLICATION_JSON',
                requestBody: """{"tag_name": "${tagName}", "ref": "main"}""",
                customHeaders: [[name: 'PRIVATE-TOKEN', value: GITLAB_TOKEN]],
                validResponseCodes: '201,409' // 409: Already exists
            )
            
            if (response.status == 201) {
                echo "Successfully created tag: ${tagName}"
            } else if (response.status == 409) {
                echo "Tag ${tagName} already exists."
            }
        }
    }
}
