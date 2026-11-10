import { useEffect, useId, useRef, type ReactNode } from 'react';
import { cx } from '@/lib/cx';
import { IconBadge, type BadgeTone } from './IconBadge';
import type { IconName } from './Icon';

interface OverlayProps {
  title: ReactNode;
  icon?: { name: IconName; tone: BadgeTone };
  /** alertdialog — 경고처럼 사용자가 꼭 읽어야 하는 대화상자 */
  alert?: boolean;
  onClose?: () => void;
  children: ReactNode;
}

function useDialog(onClose?: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    const first = ref.current?.querySelector<HTMLElement>('button, a[href], input');
    first?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose?.();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      prev?.focus?.();
    };
  }, [onClose]);
  return ref;
}

function Panel({
  kind,
  title,
  icon,
  alert,
  onClose,
  children,
}: OverlayProps & { kind: 'sheet' | 'modal' }) {
  const titleId = useId();
  const ref = useDialog(onClose);
  return (
    <div
      className={cx(
        'absolute inset-0 z-10 flex justify-center bg-scrim @pc:items-center @pc:p-8',
        kind === 'sheet' ? 'items-end' : 'items-center p-5',
      )}
    >
      <div
        ref={ref}
        role={alert ? 'alertdialog' : 'dialog'}
        aria-modal="true"
        aria-labelledby={titleId}
        className={cx(
          'grid max-h-full gap-3 overflow-y-auto bg-surface',
          kind === 'sheet'
            ? 'w-full rounded-t-r4 px-5 pb-8 pt-6 @pc:w-[440px] @pc:rounded-r4 @pc:pb-6'
            : 'w-full rounded-r4 px-5 pb-5 pt-6 shadow-sh2 @pc:max-w-[460px]',
        )}
      >
        {kind === 'sheet' && (
          <span
            aria-hidden="true"
            className="-mt-3 h-1 w-10 justify-self-center rounded-sm bg-line @pc:hidden"
          />
        )}
        {icon && <IconBadge name={icon.name} tone={icon.tone} />}
        <h2 id={titleId} className="text-title font-extrabold tracking-[-0.01em]">
          {title}
        </h2>
        {children}
      </div>
    </div>
  );
}

/** 아래 시트 — 모바일은 아래에서 올라오고, PC 는 가운데 440 대화상자가 된다(패스 확인). */
export function Sheet(props: OverlayProps) {
  return <Panel kind="sheet" {...props} />;
}

/** 가운데 대화상자(부정행위 경고 · 그룹 결과). */
export function Modal(props: OverlayProps) {
  return <Panel kind="modal" {...props} />;
}
