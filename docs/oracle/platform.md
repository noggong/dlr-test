# ORACLE — platform (로컬 실행 · 저장소)

> 판 v1 · 2026-11-11 · 짝 문서: [HLD](../hld/platform.md) · [LLD](../lld/platform.md) · [SPEC](../spec/platform.md)

**세 줄 요약**
- 기준 7개(O1~O7) + 불변식 2개(I1~I2) — 로컬 실행 계약(SPEC)과 저장소 설정이 「설계대로 됐다」를 판정한다.
- 확인 명령은 모두 레포 루트에서 돈다: `docker compose config` · `./iac/local.sh up` · `docker compose exec` · 백엔드 테스트.
- 판정 밖: 앱 수준 정합성(NFR-1~4)과 재시작 뒤 진행 상태 유지(NFR-5 의 앱 부분) — 기능이 501 스켈레톤이라 아직 판정할 대상이 없다.

## 0. 확인 명령
| 명령 | 무엇 | 환경 |
|---|---|---|
| `docker compose config --quiet` | compose 정의 검사 | Docker Compose v2.20+ (`include` 지원) |
| `./iac/local.sh up` | 빌드 · 기동 · 헬스 확인 | Docker · curl |
| `docker compose exec -T redis redis-cli …` | Redis 설정 · 값 확인 | 서비스가 떠 있을 때 |
| `cd backend && ./gradlew test` | 백엔드 테스트(라우트 스모크 · 아키텍처 규칙) | JDK 21 |

공통 통과 조건: 명령 **종료 코드 0** + 표의 「통과 조건」.

## 1. 기준
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O1 | compose 정의가 유효하고 서비스가 5개다 | SPEC §1 | `docker compose config --services` | `redis` · `postgres` · `api` · `realtime` · `web` 다섯 줄 |
| O2 | 루트에서 한 번에 뜬다 | prd §10 · SPEC §3 | `./iac/local.sh up` | 종료 0 · `api` · `realtime` · `web` 헬스 200 |
| O3 | Redis AOF 설정 | SPEC P1 | `docker compose exec -T redis redis-cli config get appendonly` · `… appendfsync` | `yes` · `everysec` |
| O4 | Redis 재시작 뒤 값 유지 | HLD §4 NFR-5 · SPEC P1 | `redis-cli set oracle:o4 1` → 2초 기다림 → `docker compose restart redis` → `redis-cli get oracle:o4` → `redis-cli del oracle:o4` | `get` 결과 `1` |
| O5 | 데이터 볼륨이 이름 있는 볼륨이다 | SPEC P2 | `docker volume ls` | `group-mission_postgres-data` · `group-mission_redis-data` 가 있다 |
| O6 | 백엔드 테스트 통과 | LLD §1 · `docs/api/openapi.yaml` | `cd backend && ./gradlew test` | 종료 0 |
| O7 | 내리기 | SPEC §3 | `./iac/local.sh down` 뒤 `docker compose ps -q` | 출력 없음 · O5 의 볼륨은 남아 있다 |

## 2. 동시성 · 불변식
| id | 불변식 | 확인 방법 | 통과 조건 |
|---|---|---|---|
| I1 | 레포에 비밀 값이 없다 | `grep -rnE "api[_-]?key\|secret\|token\|password" iac compose.yaml` | 기본값(`mission-local`) · 이름(`POSTGRES_PASSWORD`) 말고 실제 값 0 |
| I2 | 서비스 정의는 `iac/compose.yaml` 한 곳 | 루트 `compose.yaml` | `include` 와 `name` 만 있다 |

## 3. 판정 밖
- NFR-1 ~ NFR-4 와 NFR-5 의 앱 부분(재시작 뒤 진행 상태 · Stream → PostgreSQL 재적재) — 기능 구현과 함께 기능별 ORACLE 이 정한다.
- 250만 규모 부하(HLD §7) — 로컬 compose 로는 잴 수 없다. 부하 측정은 `solve-signal` · `realtime-board` 의 ORACLE 이 정한다.
- 운영 환경 확장(HLD §9) — 문서로만 설명한다(prd §10).
