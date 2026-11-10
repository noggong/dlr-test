import { useNavigate } from 'react-router-dom';
import { AppShell } from '@/components/AppShell';
import { Board } from '@/components/Board';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { Modal, Sheet } from '@/components/Overlay';
import { QuestionCard } from '@/components/QuestionCard';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
import { Toast } from '@/components/Toast';
import { useScreenState } from '@/hooks/useScreenState';
import { groups, quiz } from '@/mocks';

const states = ['start', 'wrong', 'pass', 'passed', 'live', 'last', 'warn', 'closed'] as const;
type QuizState = (typeof states)[number];

/** 제출 버튼이 다음에 여는 상태(플로우맵 배선 · SPEC §4). 정답 판정은 클라이언트 해시 비교 — 여기서는 시연만. */
const nextOnSubmit: Record<QuizState, string> = {
  start: '/quiz?state=wrong',
  wrong: '/quiz?state=wrong',
  pass: '/quiz?state=pass',
  passed: '/quiz?state=live',
  live: '/quiz?state=last',
  last: '/done?state=ok',
  warn: '/quiz?state=warn',
  closed: '/quiz?state=closed',
};

/** 4 · 문제 풀이. 모바일은 현황판이 위 · 문제가 아래, PC 는 오른쪽 현황판 380. */
export default function QuizPage() {
  const state = useScreenState(states, 'start');
  const navigate = useNavigate();
  const key: QuizState = state === 'loading' || state === 'empty' ? 'start' : state;
  const d = quiz[key];
  const closed = key === 'closed';

  return (
    <AppShell
      layout="split"
      group={groups.main}
      clock={
        closed ? { text: '마감', tone: 'end' } : { deadlineSeconds: d.deadlineSeconds, tone: 'hot' }
      }
      overlay={
        <>
          {key === 'pass' && (
            <Sheet
              title={`${d.question.no}번 문제를 패스할까요?`}
              icon={{ name: 'pass', tone: 'acc' }}
              onClose={() => navigate('/quiz?state=start')}
            >
              <p className="text-ink-2">
                {d.question.no}번 그대로, <b>같은 난이도의 다른 문제</b>로 바꿔요. 진행도는 오르지
                않아요.
              </p>
              <p className="text-small text-ink-3">
                패스는 하루에 한 번만 쓸 수 있고, 넘긴 문제는 다시 나오지 않아요. 몇 번에서
                패스했는지는 같은 진행도끼리 순위를 정할 때 반영돼요.
              </p>
              <div className="grid grid-cols-2 gap-2">
                <Button variant="secondary" to="/quiz?state=start">
                  그대로 풀기
                </Button>
                <Button to="/quiz?state=passed">패스하기</Button>
              </div>
            </Sheet>
          )}
          {key === 'warn' && (
            <Modal
              alert
              title="부정행위로 실패 처리될 수 있어요"
              icon={{ name: 'alert', tone: 'warn' }}
              onClose={() => navigate('/quiz?state=last')}
            >
              <p className="text-ink-2">
                브라우저를 닫거나, 다른 탭으로 가거나, 개발자 도구를 열면 저장된 풀이 상태가 지워질
                수 있어요. 지워지면 오늘 미션은 <b>실패</b>로 처리돼요.
              </p>
              <Button to="/quiz?state=last">계속 풀기</Button>
            </Modal>
          )}
          {d.toast && (
            <Toast>
              <b>{d.toast.id}</b> 님이 {d.toast.progress}번까지 풀었어요
            </Toast>
          )}
        </>
      }
    >
      <Board
        aside
        compact
        title="그룹 현황"
        summary={d.board.summary}
        groupStatus={d.board.group}
        rows={state === 'empty' ? [] : d.board.rows}
        empty="아직 문제를 푼 그룹원이 없어요"
        loading={state === 'loading'}
      />
      {key === 'passed' && (
        <Callout>
          패스했어요 — {d.question.no}번 문제가 같은 난이도의 다른 문제로 바뀌었어요.
        </Callout>
      )}
      {closed && (
        <Callout tone="bad" icon="lock" role="status">
          <b>08:00 마감</b> — 더는 제출할 수 없어요. 그룹 결과를 확정하는 중이에요.
        </Callout>
      )}
      {state === 'loading' ? (
        <LoadingRegion>
          <SkeletonCard lines={4} />
        </LoadingRegion>
      ) : (
        <QuestionCard
          key={key}
          question={d.question}
          defaultAnswer={d.answer}
          error={key === 'wrong' ? '틀렸어요. 같은 문제를 다시 풀어 보세요.' : undefined}
          passUsed={d.passUsed}
          locked={closed}
          onPass={() => navigate('/quiz?state=pass')}
          onSubmit={() => navigate(nextOnSubmit[key])}
        />
      )}
    </AppShell>
  );
}
