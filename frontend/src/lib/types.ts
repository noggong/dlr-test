/** 상태는 세 가지뿐 — 진행 중(파랑) · 성공(초록) · 실패(빨강). */
export type Tone = 'ing' | 'ok' | 'bad';

/** 진행도 -1(미션 시작 전)은 화면에 0/3 으로 그린다. */
export type Progress = -1 | 0 | 1 | 2 | 3;

/** 상태 칩 글 — 텍스트 라벨이 있어 색에만 기대지 않는다. */
export const statusLabel: Record<Tone, string> = { ing: '진행 중', ok: '성공', bad: '실패' };
