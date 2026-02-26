# CoWatch 시스템 설계 문서

## 1. 시스템 개요

GitLab CI의 Push/MR 이벤트를 `.gitlab-ci.yml`에서 감지하고, GitLab Runner(Jenkins 서버에 설치)가 스크립트를 실행하여 Jenkins Job을 트리거한다. Jenkins Scripted Pipeline이 충돌 분석 및 알림 처리를 담당한다.

별도 서버 없이 **기존 GitLab CI + Jenkins 인프라**를 활용한다.

---

## 2. 전체 아키텍처

```
기능 브랜치 Push
      │
      ▼ .gitlab-ci.yml (push 이벤트)
[GitLab Runner] ──► cowatch-check.sh {브랜치명}
                          │
                          ▼ Jenkins API 호출
                    Job: cowatch-conflict-check
                    (Scripted Pipeline / Groovy)
                          │
              ┌───────────┴───────────┐
              │  1. 비활성 태그 확인   │
              │  2. 충돌 분석          │
              │     - vs test 브랜치  │
              │     - vs sanity 브랜치│
              │  3. MR Discussion 생성│
              └───────────────────────┘

main 브랜치 Push (MR 머지 완료 시)
      │
      ▼ .gitlab-ci.yml (main 브랜치 push 이벤트)
[GitLab Runner] ──► cowatch-inactive.sh {머지된 브랜치명}
                          │
                          ▼ Jenkins API 호출
                    Job: cowatch-branch-inactive
                    (Scripted Pipeline / Groovy)
                          │
                    Git Tag 생성:
                    cowatch/inactive/{브랜치명}

release?-YYYYMMDDHHMMSS 태그 Push (최종 배포)
      │
      ▼ .gitlab-ci.yml (tag push 이벤트)
[GitLab Runner] ──► cowatch-release.sh {태그명}
                          │
                          ▼ Jenkins API 호출
                    Job: cowatch-release-tracker
                    (Scripted Pipeline / Groovy)
                          │
              ┌───────────┴───────────┐
              │  1. 이전 release 태그 조회│
              │  2. 두 태그 간 Compare   │
              │  3. 머지 커밋 메시지 분석 │
              │  4. 배포 브랜치 최종 확정 │
              └───────────────────────┘
```

---

## 3. 브랜치 활성 여부 관리: Git Tag 마커

DB 없이 GitLab의 Git Tag를 활용하여 브랜치 상태를 관리한다.

### 3.1. 비활성 처리

기능 브랜치가 `main`에 머지되면, `cowatch-branch-inactive` Job이 Lightweight Tag를 생성한다.

```
태그 네이밍: cowatch/inactive/{브랜치명}
예시:        cowatch/inactive/CHS2602-12345_1
```

```
POST /api/v4/projects/:id/repository/tags
  {
    "tag_name": "cowatch/inactive/CHS2602-12345_1",
    "ref":      "CHS2602-12345_1"
  }
```

### 3.2. 활성 여부 확인

Jenkins Pipeline에서 태그 존재 여부로 활성/비활성 판단한다.

```
GET /api/v4/projects/:id/repository/tags/cowatch%2Finactive%2F{브랜치명}

→ 404 Not Found : 활성 브랜치 → 충돌 분석 진행
→ 200 OK        : 비활성 브랜치 → 분석 Skip
```

---

## 4. GitLab CI 설정 (`.gitlab-ci.yml`)

### 4.1. 추가할 Job 2개

```yaml
stages:
  - deploy       # 기존 스테이지
  - cowatch      # 추가

# 기존 Job (변경 없음)
deploy:
  stage: deploy
  rules:
    - if: '$CI_COMMIT_BRANCH =~ /^CHS[0-9]{4}-[0-9]{5}_[0-9]+$/'
  script:
    - deploy-gitlab.sh $CI_COMMIT_REF_NAME

# [신규] 충돌 분석 Job
cowatch-conflict-check:
  stage: cowatch
  rules:
    - if: '$CI_COMMIT_BRANCH =~ /^CHS[0-9]{4}-[0-9]{5}_[0-9]+$/'
  script:
    - cowatch-check.sh $CI_COMMIT_REF_NAME $CI_PROJECT_ID

# [신규] 비활성 처리 Job (main으로의 push = MR 머지 완료)
cowatch-branch-inactive:
  stage: cowatch
  rules:
    - if: '$CI_COMMIT_BRANCH == "main"'
  script:
    # 머지된 커밋 SHA를 전달하여 MR API를 통해 소스 브랜치 추출
    - cowatch-inactive.sh "$CI_COMMIT_SHA" $CI_PROJECT_ID

# [신규] 릴리즈 추적 Job
cowatch-release-tracker:
  stage: cowatch
  rules:
    - if: '$CI_COMMIT_TAG =~ /^release.?-([0-9]{14})$/'
  script:
    - cowatch-release.sh $CI_COMMIT_TAG $CI_PROJECT_ID
```

### 4.2. Runner 스크립트 3개 (Jenkins 서버에 배치)

**`cowatch-check.sh`**: Jenkins Job 트리거
```bash
#!/bin/bash
BRANCH_NAME=$1
PROJECT_ID=$2

curl -X POST \
  "http://jenkins.internal/job/cowatch-conflict-check/buildWithParameters" \
  --user "jenkins-user:${JENKINS_API_TOKEN}" \
  --data "BRANCH_NAME=${BRANCH_NAME}&PROJECT_ID=${PROJECT_ID}"
```

**`cowatch-inactive.sh`**: Jenkins Job 트리거
```bash
#!/bin/bash
COMMIT_SHA=$1
PROJECT_ID=$2

curl -X POST \
  "http://jenkins.internal/job/cowatch-branch-inactive/buildWithParameters" \
  --user "jenkins-user:${JENKINS_API_TOKEN}" \
  --data "COMMIT_SHA=${COMMIT_SHA}&PROJECT_ID=${PROJECT_ID}"
```

**`cowatch-release.sh`**: Jenkins Job 트리거
```bash
#!/bin/bash
TAG_NAME=$1
PROJECT_ID=$2

curl -X POST \
  "http://jenkins.internal/job/cowatch-release-tracker/buildWithParameters" \
  --user "jenkins-user:${JENKINS_API_TOKEN}" \
  --data "TAG_NAME=${TAG_NAME}&PROJECT_ID=${PROJECT_ID}"
```

### 4.4. Tag Push Event → Job: `cowatch-release-tracker`

**Jenkins Pipeline 처리 흐름:**
```
1. 현재 생성된 release?-YYYYMMDDHHMMSS 태그명 수신
2. GitLab API로 직전 release* 패턴의 태그명 조회
3. Compare API 실행 (from 직전태그 to 현재태그) → 변경된 커밋 SHA 목록 추출
4. 각 커밋 SHA에 대해 "Commits MR API" 호출
   - GET /api/v4/projects/:id/repository/commits/:sha/merge_requests
5. 반환된 MR 정보에서 source_branch를 추출하여 유니크한 목록 생성
6. 추출된 브랜치 목록을 '최종 배포 완료' 상태로 기록 (또는 로그 출력)
```

---

## 5. 충돌 분석

### GitLab Merge Check API 활용

임시 MR을 생성하여 GitLab이 충돌 여부를 계산하게 한다.

```
# Step 1: 임시 MR 생성
POST /api/v4/projects/:id/merge_requests
  {
    "source_branch": "CHS2602-12345_1",
    "target_branch": "test",
    "title":         "[cowatch-temp] conflict-check"
  }

# Step 2: merge_status 확인
→ "can_be_merged"    : 충돌 없음
→ "cannot_be_merged" : 충돌 있음

# Step 3: 임시 MR 삭제
DELETE /api/v4/projects/:id/merge_requests/:iid
```

### 충돌 확인 대상

| 기능 브랜치 | 확인 대상 브랜치 |
|---|---|
| `CHS*` | `test` |
| `CHS*` | `sanity` |

---

## 6. 머지 차단 및 개발자 알림

### 6.1. GitLab Premium 설정

```
GitLab MR 설정 → "All threads must be resolved before merging" ✅
```

### 6.2. Discussion Thread 생성 형식

```
[CoWatch Bot] ⚠️ 충돌 예상 감지

**대상 브랜치**: test
**감지 시각**: 2026-02-19 10:30:00

머지 전에 충돌을 해결하고 이 스레드를 Resolve 해주세요.
Resolve 전까지 머지가 차단됩니다.
```

### 6.3. 개발자 인지 흐름

```
Jenkins가 MR에 Discussion 생성
      │
      ▼
Git Notify 익스텐션 → 개발자에게 팝업 알림
      │
      ▼
개발자가 충돌 처리 후 Thread Resolve
      │
      ▼
머지 가능 상태로 전환
```

---

## 7. Jenkins 구성

### 7.1. 필요 플러그인

| 플러그인 | 용도 |
|---|---|
| **HTTP Request Plugin** | GitLab API 호출 (`httpRequest`) |
| **Pipeline** | Scripted Pipeline 실행 |

> Generic Webhook Trigger 플러그인 불필요. `.gitlab-ci.yml`이 트리거를 담당한다.

### 7.2. Jenkins Credential 등록

| ID | 종류 | 값 |
|----|------|-----|
| `gitlab-api-token` | Secret Text | GitLab Bot 계정 Access Token |

### 7.3. Jenkins Job 구성

| Job 이름 | 트리거 | 파라미터 | 역할 |
|---|---|---|---|
| `cowatch-conflict-check` | `cowatch-check.sh` 호출 | `BRANCH_NAME`, `PROJECT_ID` | 충돌 분석 및 알림 |
| `cowatch-branch-inactive` | `cowatch-inactive.sh` 호출 | `COMMIT_SHA`, `PROJECT_ID` | 비활성 Tag 생성 |
| `cowatch-release-tracker` | `cowatch-release.sh` 호출 | `TAG_NAME`, `PROJECT_ID` | 릴리즈 포함 브랜치 추적 |

---

## 9. 릴리즈 추적 상세 (Release Audit)

### 9.1. 배포 브랜치 추출 알고리즘 (Pseudo-code)

```groovy
// 1. 이전 태그 조회 (release?-20260219... -> release?-20260218...)
def prevTag = gitlab.getPreviousReleaseTag(currentTag)

// 2. Diff 체크 (Compare API)
def diff = gitlab.compare(prevTag, currentTag)

// 3. 포함된 기능 브랜치 추출 (MR API 기반)
def releaseBranches = []
diff.commits.each { commit ->
    // 각 커밋이 포함된 MR 정보 조회
    def mrs = gitlab.getMergeRequestsForCommit(commit.id)
    mrs.each { mr ->
        // 새로운 명명 규칙 패턴 매칭
        if (mr.source_branch ==~ /CHS[0-9]{4}-[0-9]{5}_[0-9]+/) {
            releaseBranches << mr.source_branch
        }
    }
}
releaseBranches = releaseBranches.unique()

// 4. 결과 출력/기록 (Jenkins Log 또는 별도 리포트)
println "이번 릴리즈(${currentTag})에 포함된 배포 브랜치: ${releaseBranches}"
```

---

## 8. 환경 설정

### Jenkins Job 파라미터

| 파라미터 | 값 |
|---|---|
| `GITLAB_URL` | `https://gitlab.internal.company.com` |
| `GITLAB_CREDENTIAL_ID` | `gitlab-api-token` |
| `CONFLICT_TARGET_BRANCHES` | `test,sanity` |
| `INACTIVE_TAG_PREFIX` | `cowatch/inactive` |

### GitLab CI 환경변수 (GitLab Runner)

| 변수 | 값 |
|---|---|
| `JENKINS_API_TOKEN` | Jenkins API Token (GitLab CI Variable로 등록) |
