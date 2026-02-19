/**
 * CoWatch Release Tracker
 * release?-YYYYMMDDHHMMSS 태그 생성 시 이전 태그와의 변경분(Diff)을 분석하여
 * 이번 릴리즈에 포함된 기능 브랜치(CHSYYMM-NNNNN_N) 목록을 MR API를 통해 역추적합니다.
 */

node {
    def gitlabUrl = "${GITLAB_URL}"
    def gitlabTokenId = "${GITLAB_CREDENTIAL_ID}"
    def projectId = params.PROJECT_ID
    def currentTag = params.TAG_NAME

    stage('Track Release Branches') {
        echo "Tracking branches for release: ${currentTag}..."
        
        withCredentials([string(credentialsId: gitlabTokenId, variable: 'GITLAB_TOKEN')]) {
            // 1. 이전 release... 태그 조회
            def tagsResponse = httpRequest(
                url: "${gitlabUrl}/api/v4/projects/${projectId}/repository/tags?search=release",
                method: 'GET',
                customHeaders: [[name: 'PRIVATE-TOKEN', value: GITLAB_TOKEN]]
            )
            def tags = readJSON text: tagsResponse.content
            def prevTag = null
            
            // 현재 태그보다 먼저 생성된 최신 release 태그 찾기
            for (int i = 0; i < tags.size(); i++) {
                if (tags[i].name == currentTag) {
                    if (i + 1 < tags.size()) {
                        prevTag = tags[i+1].name
                    }
                    break
                }
            }

            if (!prevTag) {
                echo "No previous release tag found. This might be the first release."
                return
            }
            echo "Comparing ${prevTag} -> ${currentTag}"

            // 2. 두 태그 간 Compare (커밋 목록 추출)
            def compareResponse = httpRequest(
                url: "${gitlabUrl}/api/v4/projects/${projectId}/repository/compare?from=${prevTag}&to=${currentTag}",
                method: 'GET',
                customHeaders: [[name: 'PRIVATE-TOKEN', value: GITLAB_TOKEN]]
            )
            def compareResult = readJSON text: compareResponse.content
            def releaseBranches = []

            // 3. 각 커밋 SHA에 대해 MR 정보 조회 및 브랜치 추출
            compareResult.commits.each { commit ->
                def sha = commit.id
                def mrResponse = httpRequest(
                    url: "${gitlabUrl}/api/v4/projects/${projectId}/repository/commits/${sha}/merge_requests",
                    method: 'GET',
                    customHeaders: [[name: 'PRIVATE-TOKEN', value: GITLAB_TOKEN]]
                )
                def mrs = readJSON text: mrResponse.content
                
                mrs.each { mr ->
                    if (mr.source_branch ==~ /CHS[0-9]{4}-[0-9]{5}_[0-9]/) {
                        releaseBranches << mr.source_branch
                    }
                }
            }

            releaseBranches = releaseBranches.unique().sort()

            // 4. 결과 리포트 출력
            echo "--------------------------------------------------"
            echo "🚀 [CoWatch Release Audit] 최종 배포 브랜치 명단"
            echo "릴리즈 태그: ${currentTag}"
            echo "이전 태그: ${prevTag}"
            echo "포함된 브랜치 목록:"
            if (releaseBranches.size() > 0) {
                releaseBranches.each { br ->
                    echo " - ${br}"
                }
            } else {
                echo " (CHS... 패턴 브랜치 머지 내역 없음)"
            }
            echo "--------------------------------------------------"
        }
    }
}
