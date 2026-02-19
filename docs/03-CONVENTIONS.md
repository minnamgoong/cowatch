# CoWatch 프로젝트 컨벤션 가이드

이 문서는 CoWatch 프로젝트의 원활한 협업을 위한 커밋 메시지 및 개발 규칙을 정의합니다.

## 1. 커밋 메시지 컨벤션

커밋 메시지는 아래의 접두어(Prefix)를 사용하여 작성합니다.

| Prefix | 설명 |
| :--- | :--- |
| **feat** | 새로운 기능 추가 |
| **fix** | 버그 수정 |
| **docs** | 문서 수정 (README, 설계 문서, 주석 등) |
| **style** | 코드 의미에 영향을 주지 않는 변경 (화이트스페이스, 포맷팅 등) |
| **refactor** | 버그 수정이나 기능 추가가 없는 코드 로직 개선 |
| **test** | 테스트 코드 추가 및 수정 |
| **chore** | 빌드 업무 수정, 패키지 매니저 설정 등 기타 작업 |

### 커밋 메시지 형식
```
<type>: <description>
```
예시: `feat: add release tracking logic based on MR API`

---

## 2. 브랜치 명명 규칙

- **기능 브랜치**: `CHSYYMM-NNNNN_N` (예: `CHS2602-12345_1`)
- **릴리즈 태그**: `release?-YYYYMMDDHHMMSS` (예: `release1-20260219162455`)

---

## 3. 머지 플로우

1. `기능 브랜치` -> `sanity` 머지 (MR 이용)
2. `sanity` -> `main` 머지 (MR 이용)
3. `main` 머지 완료 후 자동으로 `cowatch/inactive/*` 태그 생성 및 비활성 처리
