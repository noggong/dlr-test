import { useCallback, useEffect, useState } from 'react';
import { useSession } from '@/app/session';
import { inWindow, NOTICE_TEXT } from '@/game/flow';
import { useQuizFlow } from '@/game/useQuizFlow';
import { useLiveBoard } from '@/realtime/useLiveBoard';
import { liveBoardProps, useProgressToast } from './liveView';
import { QuizView, type QuizNotice } from './QuizView';

/**
 * 문제 풀이 컨테이너 — `useQuizFlow`(SPEC §6.4) · `useLiveBoard`(W26 ~ W29)로 `QuizView` 를 채운다.
 * 그룹 결과가 확정되면 `/done` 의 대화상자로(W29), 서버 기준 08:00 이면 `/result`(W25).
 */
export default function QuizPage() {
  const { me, today, clock } = useSession();
  const flow = useQuizFlow({ me: me ?? '', clock });
  // 실시간은 게임 창 안에서 연 지면에서만(W26) — 창 밖이면 흐름이 곧 다른 지면으로 보낸다
  const [liveOn] = useState(() => inWindow(flow.window, clock.nowMs()));
  const live = useLiveBoard({ viewerId: me ?? undefined, enabled: liveOn });
  const toast = useProgressToast(live.lastEvent, me);

  const { leave } = flow;
  const decided = live.groupStatus === 'SUCCEEDED' || live.groupStatus === 'FAILED';
  useEffect(() => {
    if (decided) leave('/done');
  }, [decided, leave]);

  const now = useCallback(() => clock.nowMs(), [clock]);
  if (today.status !== 'ready') return null;
  const t = today.today;

  const notices: QuizNotice[] = [];
  if (flow.cannotJudge) notices.push({ tone: 'warn', text: NOTICE_TEXT.cannotJudge });
  if (flow.storageFailed) notices.push({ tone: 'warn', text: NOTICE_TEXT.storageFailed });
  if (flow.notice === 'serverError') {
    notices.push({
      tone: 'bad',
      text: NOTICE_TEXT.serverError,
      action: flow.status === 'blocked' ? { label: '다시 시도', onClick: flow.retry } : undefined,
    });
  } else if (flow.notice === 'notParticipant') {
    notices.push({
      tone: 'info',
      text: NOTICE_TEXT.notParticipant,
      action: { label: '결과 보기', to: flow.noticeThen ?? '/result' },
    });
  } else if (flow.notice === 'exhausted') {
    notices.push({ tone: 'warn', text: NOTICE_TEXT.exhausted });
  }

  const q = flow.question;
  return (
    <QuizView
      group={{ name: t.group.name, size: `${t.group.members.length}명` }}
      clock={{ deadline: { until: flow.window.deadlineMs, now }, tone: 'hot' }}
      board={liveBoardProps(live)}
      question={q ? { no: flow.questionNo, level: q.difficulty, text: q.body } : undefined}
      questionKey={q?.questionId}
      loading={flow.status === 'loading'}
      wrong={flow.wrong}
      passUsed={flow.passUsed}
      busy={flow.busy || flow.cannotJudge}
      closed={flow.closed}
      passAsk={flow.passAsk}
      passed={flow.passed}
      warn={flow.warn}
      toast={toast}
      notices={notices}
      onPass={flow.askPass}
      onPassCancel={flow.cancelPass}
      onPassConfirm={flow.confirmPass}
      onWarnClose={flow.closeWarn}
      onSubmit={flow.submit}
    />
  );
}
