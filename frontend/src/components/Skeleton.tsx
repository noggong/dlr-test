import type { ReactNode } from 'react';
import { cx } from '@/lib/cx';

interface SkeletonProps {
  className?: string;
  /** 줄 수 — 여러 줄 뼈대 */
  lines?: number;
}

/** 불러오는 동안 같은 자리에 세우는 뼈대(--surface-2 막대). 자리를 바꾸지 않는다. */
export function Skeleton({ className, lines = 1 }: SkeletonProps) {
  return (
    <span aria-hidden="true" className="grid gap-2">
      {Array.from({ length: lines }, (_, i) => (
        <span
          key={i}
          className={cx(
            'block h-4 animate-pulse rounded-[6px] bg-surface-2 motion-reduce:animate-none',
            i === lines - 1 && lines > 1 && 'w-2/3',
            className,
          )}
        />
      ))}
    </span>
  );
}

/** 지면 전체가 불러오는 중임을 화면 읽기 프로그램에 알린다. */
export function LoadingRegion({ children }: { children: ReactNode }) {
  return (
    <div role="status" aria-busy="true" aria-label="불러오는 중" className="contents">
      {children}
    </div>
  );
}
