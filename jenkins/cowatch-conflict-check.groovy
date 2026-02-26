/**
 * CoWatch Conflict Checker
 * 기능 브랜치 Push 시 test/sanity 브랜치와의 충돌을 감지하고 GitLab MR에 알림을 생성합니다.
 */

node {
    // 환경 변수 설정
    def gitlabUrl = "${GITLAB_URL}"
    def gitlabTokenId = "${GITLAB_CREDENTIAL_ID}"
    def projectId = params.PROJECT_ID
    def branchName = params.BRANCH_NAME
    // [CHSYYMM-NNNNN_N+] 패턴 확인
    if (!(branchName ==~ /CHS[0-9]{4}-[0-9]{5}_[0-9]+/)) {
        echo "Branch ${branchName} does not match the CHSYYMM-NNNNN_N+ pattern. Skipping."
        return
    }
    def targetBranches = params.CONFLICT_TARGET_BRANCHES.split(',')
    def inactiveTagPrefix = params.INACTIVE_TAG_PREFIX

    stage('Check Inactive Tag') {
        echo "Checking if branch ${branchName} is inactive..."
        
        withCredentials([string(credentialsId: gitlabTokenId, variable: 'GITLAB_TOKEN')]) {
            try {
                def response = httpRequest(
                    url: "${gitlabUrl}/api/v4/projects/${projectId}/repository/tags/${inactiveTagPrefix.replace('/', '%2F')}%2F${branchName.replace('/', '%2F')}",
                    method: 'GET',
                    customHeaders: [[name: 'PRIVATE-TOKEN', value: GITLAB_TOKEN]],
                    validResponseCodes: '200,404'
                )
                
                if (response.status == 200) {
                    echo "Branch ${branchName} is marked as INACTIVE. Skipping analysis."
                    currentBuild.result = 'SUCCESS'
                    return
                }
            } catch (e) {
                echo "Error checking tag: ${e.message}"
            }
        }
    }

    stage('Conflict Analysis') {
        withCredentials([string(credentialsId: gitlabTokenId, variable: 'GITLAB_TOKEN')]) {
            // 1. 현재 브랜치의 MR IID 조회
            def mrResponse = httpRequest(
                url: "${gitlabUrl}/api/v4/projects/${projectId}/merge_requests?source_branch=${branchName}&state=opened",
                method: 'GET',
                customHeaders: [[name: 'PRIVATE-TOKEN', value: GITLAB_TOKEN]]
            )
            def mrs = readJSON text: mrResponse.content
            if (mrs.size() == 0) {
                echo "No open MR found for branch ${branchName}. Skipping."
                return
            }
            def mrIid = mrs[0].iid

            // 2. 각 대상 브랜치(test, sanity)와 충돌 체크
            targetBranches.each { target ->
                echo "Analyzing conflict: ${branchName} -> ${target}"
                
                // 임시 MR 생성 (Conflict Check용)
                def createMrResponse = httpRequest(
                    url: "${gitlabUrl}/api/v4/projects/${projectId}/merge_requests",
                    method: 'POST',
                    contentType: 'APPLICATION_JSON',
                    requestBody: """{"source_branch": "${branchName}", "target_branch": "${target}", "title": "[CoWatch-Temp] Conflict Check ${branchName} to ${target}"}""",
                    customHeaders: [[name: 'PRIVATE-TOKEN', value: GITLAB_TOKEN]],
                    validResponseCodes: '201,409' // 409: Already exists
                )

                if (createMrResponse.status == 201) {
                    def tempMr = readJSON text: createMrResponse.content
                    def tempMrIid = tempMr.iid
                    
                    // merge_status가 계산될 때까지 잠시 대기 (GitLab 비동기 처리 대응)
                    sleep 2
                    
                    def checkResponse = httpRequest(
                        url: "${gitlabUrl}/api/v4/projects/${projectId}/merge_requests/${tempMrIid}",
                        method: 'GET',
                        customHeaders: [[name: 'PRIVATE-TOKEN', value: GITLAB_TOKEN]]
                    )
                    def checkMr = readJSON text: checkResponse.content
                    def hasConflict = (checkMr.merge_status == 'cannot_be_merged')

                    if (hasConflict) {
                        createConflictDiscussion(gitlabUrl, projectId, mrIid, target, GITLAB_TOKEN)
                    }

                    // 임시 MR 삭제
                    httpRequest(
                        url: "${gitlabUrl}/api/v4/projects/${projectId}/merge_requests/${tempMrIid}",
                        method: 'DELETE',
                        customHeaders: [[name: 'PRIVATE-TOKEN', value: GITLAB_TOKEN]]
                    )
                } else if (createMrResponse.status == 409) {
                    echo "Temporary MR for ${target} already exists."
                }
            }
        }
    }
}

def createConflictDiscussion(url, projectId, mrIid, target, token) {
    def body = """
[CoWatch Bot] ⚠️ 충돌 예상 감지

**대상 브랜치**: ${target}
**감지 시각**: ${new Date().format("yyyy-MM-dd HH:mm:ss")}

머지 전에 ${target} 브랜치와의 충돌을 해결하고 이 스레드를 Resolve 해주세요.
Resolve 전까지 머지가 차단됩니다. (GitLab Premium 정책)
"""
    httpRequest(
        url: "${url}/api/v4/projects/${projectId}/merge_requests/${mrIid}/discussions",
        method: 'POST',
        contentType: 'APPLICATION_JSON',
        requestBody: """{"body": "${body.trim().replace('\n', '\\n').replace('"', '\\"')}"}""",
        customHeaders: [[name: 'PRIVATE-TOKEN', value: token]]
    )
}
