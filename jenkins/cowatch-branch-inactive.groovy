/**
 * CoWatch Branch Inactive Marker
 * main 브랜치 머지 완료 시 해당 기능 브랜치에 비활성 태그를 생성합니다.
 */

node {
    def gitlabUrl = "${GITLAB_URL}"
    def gitlabTokenId = "${GITLAB_CREDENTIAL_ID}"
    def projectId = params.PROJECT_ID
    def commitSha = params.COMMIT_SHA
    def inactiveTagPrefix = params.INACTIVE_TAG_PREFIX

    stage('Identify Source Branch') {
        echo "Identifying source branches from commit ${commitSha} in project ${projectId}..."
        
        withCredentials([string(credentialsId: gitlabTokenId, variable: 'GITLAB_TOKEN')]) {
            def mrResponse = httpRequest(
                url: "${gitlabUrl}/api/v4/projects/${projectId}/repository/commits/${commitSha}/merge_requests",
                method: 'GET',
                customHeaders: [[name: 'PRIVATE-TOKEN', value: GITLAB_TOKEN]]
            )
            
            def mrs = readJSON text: mrResponse.content
            if (mrs.size() == 0) {
                echo "No merge requests found for commit ${commitSha}. Skipping inactive tag creation."
                return
            }
            
            // 머지된 MR에서 소스 브랜치 추출 (CHS 패턴인 경우에만)
            mrs.each { mr ->
                def sourceBranch = mr.source_branch
                if (sourceBranch ==~ /CHS[0-9]{4}-[0-9]{5}_[0-9]+/) {
                    echo "Found matching source branch: ${sourceBranch}"
                    createInactiveTag(gitlabUrl, projectId, sourceBranch, inactiveTagPrefix, GITLAB_TOKEN)
                } else {
                    echo "Branch ${sourceBranch} does not match CHS pattern. Skipping."
                }
            }
        }
    }
}

def createInactiveTag(url, projectId, branch, prefix, token) {
    def tagName = "${prefix}/${branch}"
    echo "Marking branch ${branch} as INACTIVE with tag ${tagName}..."
    def response = httpRequest(
        url: "${url}/api/v4/projects/${projectId}/repository/tags",
        method: 'POST',
        contentType: 'APPLICATION_JSON',
        requestBody: """{"tag_name": "${tagName}", "ref": "main"}""",
        customHeaders: [[name: 'PRIVATE-TOKEN', value: token]],
        validResponseCodes: '201,409' // 409: Already exists
    )
    if (response.status == 201) {
        echo "Successfully created tag: ${tagName}"
    } else if (response.status == 409) {
        echo "Tag ${tagName} already exists."
    }
}
