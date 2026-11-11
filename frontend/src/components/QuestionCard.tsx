import { useState, type FormEvent } from 'react';
import { cx } from '@/lib/cx';
import { Button } from './Button';
import { Field } from './Field';
import { Icon } from './Icon';

export interface QuestionData {
  no: 1 | 2 | 3;
  level: 1 | 2 | 3;
  text: string;
}

interface QuestionCardProps {
  question: QuestionData;
  /** 입력칸에 미리 들어 있는 답(오답 상태 재현) */
  defaultAnswer?: string;
  /** 오답 문구 — 클라이언트 해시 판정 결과(서버 신호 없음) */
  error?: string;
  /** 오늘 패스를 이미 썼다 — 그날 끝까지 「패스 사용함」으로 잠긴다 */
  passUsed?: boolean;
  /** 08:00 마감 — 입력 · 패스 · 제출 모두 잠금 */
  locked?: boolean;
  /** 신호를 보내는 중 · 판정할 수 없음 — 입력은 두고 패스 · 제출만 잠금 */
  busy?: boolean;
  /** 예시 문제 표시 */
  sample?: boolean;
  onPass?: () => void;
  onSubmit?: (answer: string) => void;
}

/** 문제 카드 — 단계 · 난이도 · 문제 · 답 입력 · 패스 / 제출. */
export function QuestionCard({
  question,
  defaultAnswer = '',
  error,
  passUsed,
  locked,
  busy,
  sample = true,
  onPass,
  onSubmit,
}: QuestionCardProps) {
  const [answer, setAnswer] = useState(defaultAnswer);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit?.(answer);
  };
  return (
    <section
      aria-label={`${question.no}번 문제`}
      className="grid gap-3.5 rounded-r3 border border-line bg-surface p-[18px] @pc:gap-[18px] @pc:p-7"
    >
      <div className="flex items-center justify-between gap-2">
        <Steps no={question.no} level={question.level} />
        {sample && <span className="text-micro font-semibold text-ink-3">예시 문제</span>}
      </div>
      <p className="text-question font-extrabold tracking-[-0.01em] @pc:text-question-pc">
        {question.text}
      </p>
      <form onSubmit={submit} className="grid gap-3.5 @pc:gap-[18px]" noValidate>
        <Field
          label="답"
          placeholder={locked ? '마감됐어요' : '답을 입력하세요'}
          value={locked ? '' : answer}
          onChange={(e) => setAnswer(e.target.value)}
          disabled={locked}
          error={error}
          errorIcon="x"
          autoComplete="off"
        />
        <div className="grid grid-cols-[auto_1fr] gap-2 @pc:grid-cols-[auto_200px] @pc:justify-end">
          <Button
            variant="secondary"
            size="sm"
            icon="pass"
            disabled={passUsed || locked || busy}
            onClick={onPass}
          >
            {passUsed ? '패스 사용함' : '패스 1회 남음'}
          </Button>
          <Button type="submit" size="sm" disabled={locked || busy}>
            제출
          </Button>
        </div>
      </form>
    </section>
  );
}

function Steps({ no, level }: { no: number; level: number }) {
  return (
    <div
      className="flex items-center gap-1.5"
      role="img"
      aria-label={`${no}번 문제 · 3문제 중 · 난이도 ${level}`}
    >
      {[1, 2, 3].map((i) => (
        <i
          key={i}
          aria-hidden="true"
          className={cx(
            'grid h-[26px] w-[26px] place-items-center rounded-full text-chip font-extrabold not-italic',
            i < no && 'bg-ok text-surface',
            i === no && 'bg-accent text-accent-on',
            i > no && 'bg-surface-2 text-ink-3',
          )}
        >
          {i < no ? <Icon name="check" size={14} strokeWidth={3} /> : i}
        </i>
      ))}
      <span className="ml-1 text-chip text-ink-3">난이도 {level}</span>
    </div>
  );
}
