import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { cx } from '@/lib/cx';
import { Icon, type IconName } from './Icon';

type Variant = 'primary' | 'secondary';
type Size = 'md' | 'sm';

interface ButtonOwnProps {
  variant?: Variant;
  size?: Size;
  icon?: IconName;
  /** 주면 링크(<a>)로 그린다. disabled 면 링크를 걸지 않는다. */
  to?: string;
  disabled?: boolean;
  className?: string;
  children: ReactNode;
}

type ButtonProps = ButtonOwnProps &
  Omit<ButtonHTMLAttributes<HTMLButtonElement>, keyof ButtonOwnProps>;

/** 주 버튼 · 보조 버튼. 잠긴 버튼(08:00 마감 · 패스 사용함)은 disabled. 높이 44 이상. */
function buttonClass({
  variant = 'primary',
  size = 'md',
  disabled,
  className,
}: Pick<ButtonOwnProps, 'variant' | 'size' | 'disabled' | 'className'>) {
  return cx(
    'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-r2 border px-4 font-bold no-underline',
    'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent',
    size === 'md' ? 'h-[52px] text-input' : 'h-11 text-body',
    disabled
      ? 'cursor-not-allowed border-line bg-surface-2 text-ink-3'
      : variant === 'primary'
        ? 'border-transparent bg-accent text-accent-on'
        : 'border-line-strong bg-surface text-ink',
    className,
  );
}

export function Button({
  variant,
  size,
  icon,
  to,
  disabled,
  className,
  children,
  type = 'button',
  ...rest
}: ButtonProps) {
  const cls = buttonClass({ variant, size, disabled, className });
  const inner = (
    <>
      {icon && <Icon name={icon} size={size === 'sm' ? 18 : 20} />}
      {children}
    </>
  );
  if (to && !disabled) {
    return (
      <Link to={to} className={cls}>
        {inner}
      </Link>
    );
  }
  return (
    <button type={type} disabled={disabled} className={cls} {...rest}>
      {inner}
    </button>
  );
}
