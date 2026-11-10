import { cx } from '@/lib/cx';
import type { Progress, Tone } from '@/lib/types';
import { Callout } from './Callout';
import { ProgressBar } from './ProgressBar';
import { Skeleton } from './Skeleton';
import { StatusChip } from './StatusChip';

export interface BoardRowData {
  /** 서버가 정한 순위(동률 허용). 클라이언트는 받은 순서대로 그린다. */
  rank: number;
  id: string;
  me?: boolean;
  progress: Progress;
  status: Tone;
  /** 아래 줄 — 마지막 풀이 신호의 서버 도착 시각 · 「n번에서 패스」 · 「실패 처리」 · 「접속하지 않음」 */
  note?: string;
}

interface BoardProps {
  title: string;
  /** 제목 옆 작은 글(예: 1/5명 완료) */
  summary?: string;
  groupStatus?: { tone: Tone; label: string };
  rows: BoardRowData[];
  /** 촘촘한 줄(40 · PC 46) — 문제 풀이 · 완료 지면의 현황판 */
  compact?: boolean;
  /** 대화상자 안에서 좌우 여백을 줄인다 */
  inset?: boolean;
  /** PC split 틀에서 오른쪽 380 열로 보낸다 */
  aside?: boolean;
  loading?: boolean;
  /** 빈 상태 안내 한 줄 */
  empty?: string;
  className?: string;
}

/** 그룹 현황판 · 그룹 내 랭킹. 그룹원 10명이 한 화면에 들어가도록 줄 높이 40~46. */
export function Board({
  title,
  summary,
  groupStatus,
  rows,
  compact,
  inset,
  aside,
  loading,
  empty,
  className,
}: BoardProps) {
  return (
    <section
      aria-label={title}
      data-aside={aside || undefined}
      className={cx(
        'overflow-hidden rounded-r3 border border-line bg-surface',
        inset && 'border-0',
        className,
      )}
    >
      <div
        className={cx(
          'flex items-center justify-between gap-2 border-b border-line py-2.5',
          inset ? 'px-1' : 'px-4',
        )}
      >
        <h2 className="text-body font-bold">
          {title}
          {summary && (
            <small className="num ml-1.5 text-chip font-semibold text-ink-3">{summary}</small>
          )}
        </h2>
        {groupStatus && <StatusChip tone={groupStatus.tone}>{groupStatus.label}</StatusChip>}
      </div>
      {loading ? (
        <div className="grid gap-3 p-4" role="status" aria-label="불러오는 중">
          <Skeleton lines={5} className="h-5" />
        </div>
      ) : rows.length === 0 && empty ? (
        <div className="p-3">
          <Callout>{empty}</Callout>
        </div>
      ) : (
        <ol>
          {rows.map((r) => (
            <BoardRow key={r.id} row={r} compact={compact} inset={inset} />
          ))}
        </ol>
      )}
    </section>
  );
}

function BoardRow({
  row,
  compact,
  inset,
}: {
  row: BoardRowData;
  compact?: boolean;
  inset?: boolean;
}) {
  return (
    <li
      aria-current={row.me || undefined}
      className={cx(
        'grid grid-cols-[28px_minmax(0,1fr)_82px_64px] items-center gap-2 border-t border-line first:border-t-0',
        compact ? 'h-10 @pc:h-[46px]' : 'h-[46px]',
        inset ? 'px-1' : 'pl-3 pr-3.5',
        row.me && 'bg-accent-soft',
      )}
    >
      <span className="num text-center text-body font-extrabold">
        <span className="sr-only">순위 </span>
        {row.rank}
      </span>
      <span className="flex min-w-0 flex-col leading-tight">
        <b className="truncate text-body font-semibold">
          {row.id}
          {row.me && (
            <em className="ml-1.5 text-micro font-extrabold not-italic text-accent-ink">나</em>
          )}
        </b>
        {row.note && <small className="num truncate text-chip text-ink-3">{row.note}</small>}
      </span>
      <ProgressBar value={row.progress} tone={row.status} />
      <StatusChip tone={row.status} className="justify-self-end" />
    </li>
  );
}
