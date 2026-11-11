import { useEffect, useMemo, useState } from 'react';
import { getBoard } from '@/api/endpoints';
import type { BoardRowData } from '@/components/Board';
import {
  createLiveBoard,
  type Connection,
  type LiveBoardDeps,
  type LiveEvent,
  type ResultData,
} from './liveBoard';
import { toBoardRows, type GroupStatus, type MemberEntry } from './mapEntry';

/** 토스트 · 대화상자용 — 화면 전체(`rows`)는 따로 갱신된다(W29). */
export type LastEvent =
  | { type: 'member.progressed'; version: number; userId: string; progress: number }
  | { type: 'group.pending'; version: number }
  | {
      type: 'group.result';
      version: number;
      groupStatus: ResultData['groupStatus'];
      rankingFinal: boolean;
    }
  | { type: 'group.ranking_final'; version: number; groupStatus: ResultData['groupStatus'] };

export interface LiveBoardView {
  rows: BoardRowData[];
  /** 서버 원본(내 상태 · 진행도를 볼 때) */
  entries: MemberEntry[];
  groupStatus: GroupStatus | undefined;
  version: number | undefined;
  lastEvent: LastEvent | undefined;
  connection: Connection;
}

export interface UseLiveBoardOptions {
  /** 로그인한 유저 — 그 줄이 `me`. */
  viewerId?: string;
  /** false 면 조회 · 연결을 하지 않는다(게임 시간 밖 등). 기본 true. */
  enabled?: boolean;
  /** 테스트용 주입 — 바꿔도 다시 연결하지 않는다(마운트 때 값). */
  deps?: LiveBoardDeps & { getBoard?: typeof getBoard };
}

interface BoardState {
  entries: MemberEntry[];
  groupStatus: GroupStatus | undefined;
  version: number | undefined;
  /** 지금 화면이 어디서 왔나 — 조회(`rest`)면 SSE 는 version 이 커야 덮는다(RB6 끝 문장) */
  source: 'rest' | 'sse' | undefined;
  lastEvent: LastEvent | undefined;
}

const DISABLED: Connection = { state: 'stopped' };

const EMPTY: BoardState = {
  entries: [],
  groupStatus: undefined,
  version: undefined,
  source: undefined,
  lastEvent: undefined,
};

/**
 * 실시간 현황판 — 처음엔 `GET /games/today/board` 와 SSE `board.snapshot` 중 version 이 큰 쪽,
 * 그 뒤로 SSE 이벤트로 갱신한다(W26 · W29). 언마운트 때 연결을 끊는다.
 */
export function useLiveBoard({
  viewerId,
  enabled = true,
  deps,
}: UseLiveBoardOptions = {}): LiveBoardView {
  const [state, setState] = useState<BoardState>(EMPTY);
  const [connection, setConnection] = useState<Connection>({ state: 'connecting' });
  // 주입값은 마운트 때 것을 쓴다 — 객체 리터럴로 넘겨도 렌더마다 다시 연결하지 않게
  const [initialDeps] = useState(deps);

  useEffect(() => {
    // 꺼지면 지난 현황판을 남기지 않는다
    setState(EMPTY);
    if (!enabled) return;
    setConnection({ state: 'connecting' });
    const controller = new AbortController();
    const fetchBoard = initialDeps?.getBoard ?? getBoard;
    fetchBoard(controller.signal).then(
      (board) => {
        if (controller.signal.aborted) return;
        setState((s) =>
          s.version !== undefined && board.version <= s.version
            ? s
            : {
                ...s,
                entries: board.entries,
                groupStatus: board.groupStatus,
                version: board.version,
                source: 'rest',
              },
        );
      },
      () => {
        // 조회 실패는 무시한다 — 실시간 연결이 같은 사정을 connection 으로 알린다
      },
    );
    const live = createLiveBoard(
      {
        onEvent: (event) => setState((s) => reduce(s, event)),
        onConnection: setConnection,
      },
      initialDeps,
    );
    live.start();
    return () => {
      controller.abort();
      live.stop();
    };
  }, [enabled, initialDeps]);

  const rows = useMemo(() => toBoardRows(state.entries, viewerId), [state.entries, viewerId]);
  return {
    rows,
    entries: state.entries,
    groupStatus: state.groupStatus,
    version: state.version,
    lastEvent: state.lastEvent,
    connection: enabled ? connection : DISABLED,
  };
}

function reduce(s: BoardState, event: LiveEvent): BoardState {
  // 조회 응답이 더 새것이면 SSE 가 그보다 커질 때까지 조회 쪽을 남긴다. 연결마다의 비교(RB6)는 liveBoard 가 했다.
  if (s.source === 'rest' && s.version !== undefined && event.data.version <= s.version) return s;
  const version = event.data.version;
  switch (event.type) {
    case 'board.snapshot':
      return {
        ...s,
        entries: event.data.entries,
        groupStatus: event.data.groupStatus,
        version,
        source: 'sse',
      };
    case 'member.progressed': {
      const { userId, entries, groupStatus } = event.data;
      const progress = entries.find((e) => e.userId === userId)?.progress ?? 0;
      return {
        entries,
        groupStatus,
        version,
        source: 'sse',
        lastEvent: { type: 'member.progressed', version, userId, progress },
      };
    }
    case 'group.pending':
      return {
        ...s,
        groupStatus: 'PENDING',
        version,
        source: 'sse',
        lastEvent: { type: 'group.pending', version },
      };
    case 'group.result': {
      const { entries, groupStatus, rankingFinal } = event.data;
      return {
        entries,
        groupStatus,
        version,
        source: 'sse',
        lastEvent: { type: 'group.result', version, groupStatus, rankingFinal },
      };
    }
    case 'group.ranking_final': {
      const { entries, groupStatus } = event.data;
      return {
        entries,
        groupStatus,
        version,
        source: 'sse',
        lastEvent: { type: 'group.ranking_final', version, groupStatus },
      };
    }
  }
}
