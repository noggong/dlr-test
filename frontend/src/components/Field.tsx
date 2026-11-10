import { useId, type InputHTMLAttributes, type ReactNode } from 'react';
import { cx } from '@/lib/cx';
import { Icon } from './Icon';

interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  label: string;
  /** strong = 가입 · 로그인 폼(굵은 라벨) · quiet = 문제 카드의 「답」 */
  labelStyle?: 'strong' | 'quiet';
  hint?: ReactNode;
  /** 오류 문구. 주면 테두리가 빨강이 되고 aria-invalid. 문구 없이 테두리만은 true */
  error?: ReactNode | true;
  errorIcon?: 'alert' | 'x';
  success?: ReactNode;
  /** 입력칸 오른쪽 끝(인원 칩 · 복사 버튼 등) */
  trailing?: ReactNode;
}

/** 라벨 + 입력칸 + 도움말/오류. 입력칸 높이 48, 글자 17. */
export function Field({
  label,
  labelStyle = 'strong',
  hint,
  error,
  errorIcon = 'alert',
  success,
  trailing,
  disabled,
  className,
  id,
  ...input
}: FieldProps) {
  const auto = useId();
  const inputId = id ?? auto;
  const descId = `${inputId}-desc`;
  const hasError = Boolean(error);
  const message = error && error !== true ? error : null;
  const describedBy = hint || message || success ? descId : undefined;

  return (
    <div className={cx('grid gap-1.5', className)}>
      <label
        htmlFor={inputId}
        className={cx(
          'text-small',
          labelStyle === 'strong' ? 'font-bold text-ink-2' : 'text-ink-3',
        )}
      >
        {label}
      </label>
      <div
        className={cx(
          'flex h-12 items-center justify-between gap-2 rounded-r2 border pr-2',
          disabled
            ? 'border-line bg-surface-2 text-ink-3'
            : hasError
              ? 'border-bad bg-surface ring-1 ring-bad'
              : 'border-line-strong bg-surface focus-within:border-accent focus-within:ring-1 focus-within:ring-accent',
        )}
      >
        <input
          id={inputId}
          disabled={disabled}
          aria-invalid={hasError || undefined}
          aria-describedby={describedBy}
          className="h-full min-w-0 flex-1 rounded-r2 bg-transparent pl-3.5 text-input text-inherit outline-none placeholder:text-ink-3 disabled:cursor-not-allowed"
          {...input}
        />
        {trailing}
      </div>
      <div id={describedBy}>
        {hint && !message && <p className="text-small text-ink-3">{hint}</p>}
        {message && (
          <p className="flex items-start gap-1.5 text-small text-bad">
            <Icon
              name={errorIcon}
              strokeWidth={errorIcon === 'x' ? 2.4 : 2}
              className="mt-0.5 flex-none"
            />
            <span>{message}</span>
          </p>
        )}
        {success && !message && (
          <p className="flex items-start gap-1.5 text-small text-ok">
            <Icon name="check" className="mt-0.5 flex-none" />
            <span>{success}</span>
          </p>
        )}
      </div>
    </div>
  );
}
