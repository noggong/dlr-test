import type { ReactNode } from 'react';
import { cx } from '@/lib/cx';
import { IconBadge, type BadgeTone } from './IconBadge';
import type { IconName } from './Icon';

/** 지면 머리 — 상태 아이콘(장식) · 눈썹 글 · 제목 · 설명. */
export function Hero({
  icon,
  eyebrow,
  title,
  large,
  sub,
  children,
  className,
}: {
  icon?: { name: IconName; tone: BadgeTone };
  eyebrow?: string;
  title: ReactNode;
  /** 제목 L(28 / PC 32) — 지면 제목. 아니면 24 */
  large?: boolean;
  sub?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cx('grid justify-items-start gap-2 pt-2', className)}>
      {icon && <IconBadge name={icon.name} tone={icon.tone} />}
      {eyebrow && (
        <span className="text-chip font-bold tracking-[0.04em] text-accent-ink">{eyebrow}</span>
      )}
      <h1
        className={cx(
          'font-extrabold tracking-[-0.01em]',
          large ? 'text-title-l @pc:text-title-l-pc' : 'text-title',
        )}
      >
        {title}
      </h1>
      {children}
      {sub && <p className="num text-ink-2">{sub}</p>}
    </div>
  );
}

/** 카드 — 흰 면 · 선 · 라운드 16. */
export function Card({
  title,
  aside,
  children,
  className,
}: {
  title?: ReactNode;
  /** 제목 오른쪽(칩 · 숫자) */
  aside?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <section className={cx('grid gap-2.5 rounded-r3 border border-line bg-surface p-4', className)}>
      {title &&
        (aside ? (
          <div className="flex items-center justify-between gap-2">
            <h3 className="text-body font-bold leading-snug">{title}</h3>
            {aside}
          </div>
        ) : (
          <h3 className="text-body font-bold leading-snug">{title}</h3>
        ))}
      {children}
    </section>
  );
}

/** 이름-값 목록(가입 완료 요약 · 내 상태/그룹 상태). */
export function KeyValue({ items }: { items: Array<[string, ReactNode]> }) {
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-small">
      {items.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-ink-3">{k}</dt>
          <dd className="num font-bold">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export interface Person {
  id: string;
  me?: boolean;
  /** 07:00 이후 가입 — 다음 날부터 함께 */
  later?: boolean;
}

/** 그룹원 이름 알약 목록. */
export function PeopleList({ people }: { people: Person[] }) {
  return (
    <ul className="flex flex-wrap gap-1.5">
      {people.map((p) => (
        <li
          key={p.id}
          className={cx(
            'inline-flex h-7 items-center rounded-pill px-2.5 text-small font-semibold',
            p.me && 'bg-accent-soft text-accent-ink',
            p.later && 'border border-dashed border-line-strong text-ink-3',
            !p.me && !p.later && 'bg-surface-2',
          )}
        >
          {p.id}
          {p.me && ' · 나'}
          {p.later && ' · 내일부터'}
        </li>
      ))}
    </ul>
  );
}
