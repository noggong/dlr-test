import { AppShell, type ClockProps } from '@/components/AppShell';
import { Board, type BoardRowData } from '@/components/Board';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { Modal, Sheet } from '@/components/Overlay';
import { QuestionCard, type QuestionData } from '@/components/QuestionCard';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
import { Toast } from '@/components/Toast';
import type { Tone } from '@/lib/types';

export interface QuizNotice {
  tone: 'info' | 'warn' | 'bad';
  text: string;
  /** 안내 아래 버튼(다시 시도 · 결과 보기) */
  action?: { label: string; onClick?: () => void; to?: string };
}

export interface QuizViewProps {
  group?: { name: string; size: string };
  /** 상단 바 마감 시계 — 없고 `closed` 면 「마감」 */
  clock?: ClockProps;
  board: {
    summary?: string;
    groupStatus?: { tone: Tone; label: string };
    rows: BoardRowData[];
    loading?: boolean;
  };
  /** 없으면 문제 자리에 아무것도 그리지 않는다(안내만) */
  question?: QuestionData;
  /** 문제가 바뀌면 입력칸을 비운다 */
  questionKey?: string;
  /** 문제 자리 뼈대 */
  loading?: boolean;
  /** 입력칸에 미리 든 답(스토리) */
  answer?: string;
  wrong?: boolean;
  passUsed?: boolean;
  busy?: boolean;
  /** 08:00 마감 — 입력 · 패스 · 제출 잠금(W8) */
  closed?: boolean;
  passAsk?: boolean;
  passed?: boolean;
  warn?: boolean;
  toast?: { id: string; progress: number };
  notices?: QuizNotice[];
  onPass?: () => void;
  onPassCancel?: () => void;
  onPassConfirm?: () => void;
  onWarnClose?: () => void;
  onSubmit?: (answer: string) => void;
}

/** 4 · 문제 풀이 — props 만 그린다. 모바일은 현황판이 위 · 문제가 아래, PC 는 오른쪽 현황판 380(W3). */
export function QuizView({
  group,
  clock,
  board,
  question,
  questionKey,
  loading,
  answer,
  wrong,
  passUsed,
  busy,
  closed,
  passAsk,
  passed,
  warn,
  toast,
  notices = [],
  onPass,
  onPassCancel,
  onPassConfirm,
  onWarnClose,
  onSubmit,
}: QuizViewProps) {
  return (
    <AppShell
      layout="split"
      group={group}
      clock={closed ? { text: '마감', tone: 'end' } : clock}
      overlay={
        <>
          {passAsk && question && !closed && (
            <Sheet
              title={`${question.no}번 문제를 패스할까요?`}
              icon={{ name: 'pass', tone: 'acc' }}
              onClose={onPassCancel}
            >
              <p className="text-ink-2">
                {question.no}번 그대로, <b>같은 난이도의 다른 문제</b>로 바꿔요. 진행도는 오르지
                않아요.
              </p>
              <p className="text-small text-ink-3">
                패스는 하루에 한 번만 쓸 수 있고, 넘긴 문제는 다시 나오지 않아요. 몇 번에서
                패스했는지는 같은 진행도끼리 순위를 정할 때 반영돼요.
              </p>
              <div className="grid grid-cols-2 gap-2">
                <Button variant="secondary" onClick={onPassCancel}>
                  그대로 풀기
                </Button>
                <Button onClick={onPassConfirm}>패스하기</Button>
              </div>
            </Sheet>
          )}
          {warn && !closed && (
            <Modal
              alert
              title="부정행위로 실패 처리될 수 있어요"
              icon={{ name: 'alert', tone: 'warn' }}
              onClose={onWarnClose}
            >
              <p className="text-ink-2">
                브라우저를 닫거나, 다른 탭으로 가거나, 개발자 도구를 열면 저장된 풀이 상태가 지워질
                수 있어요. 지워지면 오늘 미션은 <b>실패</b>로 처리돼요.
              </p>
              <Button onClick={onWarnClose}>계속 풀기</Button>
            </Modal>
          )}
          {toast && (
            <Toast>
              <b>{toast.id}</b> 님이 {toast.progress}번까지 풀었어요
            </Toast>
          )}
        </>
      }
    >
      <Board
        aside
        compact
        title="그룹 현황"
        summary={board.summary}
        groupStatus={board.groupStatus}
        rows={board.rows}
        empty="아직 문제를 푼 그룹원이 없어요"
        loading={board.loading}
      />
      {passed && question && !closed && (
        <Callout>패스했어요 — {question.no}번 문제가 같은 난이도의 다른 문제로 바뀌었어요.</Callout>
      )}
      {closed && (
        <Callout tone="bad" icon="lock" role="status">
          <b>08:00 마감</b> — 더는 제출할 수 없어요. 그룹 결과를 확정하는 중이에요.
        </Callout>
      )}
      {notices.map((n) => (
        <div key={n.text} className="grid gap-2">
          <Callout tone={n.tone} role={n.tone === 'info' ? 'status' : 'alert'}>
            {n.text}
          </Callout>
          {n.action && (
            <Button variant="secondary" onClick={n.action.onClick} to={n.action.to}>
              {n.action.label}
            </Button>
          )}
        </div>
      ))}
      {loading ? (
        <LoadingRegion>
          <SkeletonCard lines={4} />
        </LoadingRegion>
      ) : (
        question && (
          <QuestionCard
            key={questionKey ?? `${question.no}:${question.text}`}
            question={question}
            sample={false}
            defaultAnswer={answer}
            error={wrong ? '틀렸어요. 같은 문제를 다시 풀어 보세요.' : undefined}
            passUsed={passUsed}
            locked={closed}
            busy={busy}
            onPass={onPass}
            onSubmit={onSubmit}
          />
        )
      )}
    </AppShell>
  );
}
