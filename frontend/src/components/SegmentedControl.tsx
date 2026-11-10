import { useRef, type KeyboardEvent } from 'react';
import { cx } from '@/lib/cx';

interface Option<T extends string> {
  value: T;
  label: string;
}

interface SegmentedControlProps<T extends string> {
  label: string;
  options: Option<T>[];
  value: T;
  onChange: (value: T) => void;
}

/** 둘 중 하나 고르기(새 그룹 만들기 / 기존 그룹 들어가기). 화살표 키로 옮긴다. */
export function SegmentedControl<T extends string>({
  label,
  options,
  value,
  onChange,
}: SegmentedControlProps<T>) {
  const refs = useRef<Array<HTMLButtonElement | null>>([]);

  const onKey = (e: KeyboardEvent, i: number) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    e.preventDefault();
    const next = (i + (e.key === 'ArrowRight' ? 1 : -1) + options.length) % options.length;
    onChange(options[next].value);
    refs.current[next]?.focus();
  };

  return (
    <div
      role="tablist"
      aria-label={label}
      className="grid gap-1 rounded-r2 bg-surface-2 p-1"
      style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0, 1fr))` }}
    >
      {options.map((o, i) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            ref={(el) => {
              refs.current[i] = el;
            }}
            type="button"
            role="tab"
            aria-selected={on}
            tabIndex={on ? 0 : -1}
            onClick={() => onChange(o.value)}
            onKeyDown={(e) => onKey(e, i)}
            className={cx(
              'flex h-10 items-center justify-center rounded-r1 text-body font-bold',
              'focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent',
              on ? 'bg-surface text-ink shadow-sh1' : 'text-ink-2',
            )}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
