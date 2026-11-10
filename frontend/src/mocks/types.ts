import type { BoardRowData } from '@/components/Board';
import type { Person } from '@/components/Blocks';
import type { QuestionData } from '@/components/QuestionCard';
import type { Tone } from '@/lib/types';

// 목 데이터의 모양. 서버 계약(docs/api/)과의 필드 대응은 docs/spec/web-client.md §2.1.

export interface GroupInfo {
  name: string;
  /** 상단 바의 인원 글(「5명」 · 「오늘 1명」) */
  size: string;
}

export interface BoardData {
  summary: string;
  group: { tone: Tone; label: string };
  rows: BoardRowData[];
}

export interface QuizScreen {
  /** 마감까지 남은 초. 없으면 마감(08:00) */
  deadlineSeconds?: number;
  board: BoardData;
  question: QuestionData;
  passUsed: boolean;
  answer?: string;
  /** 실시간 메시지 토스트 — 누가 몇 번까지 풀었는지 */
  toast?: { id: string; progress: number };
}

export interface DoneScreen {
  deadlineSeconds: number;
  board: BoardData;
  /** 그룹 결과 확정 대화상자 */
  result?: { tone: 'ok' | 'bad'; sub: string; ranking: BoardRowData[] };
}

export interface ResultScreen {
  now: string;
  sub: string;
  myRank: number;
  total: number;
  ranking: BoardRowData[];
  nextSeconds: number;
}

export interface WaitingScreen {
  now: string;
  seconds: number;
  headline: string;
  members: Person[];
  yesterday?: { tone: 'ok' | 'bad'; label: string; detail: string };
}

export interface SoloScreen {
  now: string;
  members: Person[];
  nextSeconds: number;
}

export interface SignupData {
  id: string;
  password: string;
  newGroup: string;
  fullGroup: string;
  done: { id: string; group: string; members: string; joinedAt: string };
}
