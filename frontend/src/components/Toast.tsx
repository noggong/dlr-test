import type { ReactNode } from 'react';
import { Icon, type IconName } from './Icon';

interface ToastProps {
  icon?: IconName;
  children: ReactNode;
}

/** 실시간 알림 한 줄. 모바일 아래 전체 폭, PC 오른쪽 아래 360. */
export function Toast({ icon = 'people', children }: ToastProps) {
  return (
    <div
      role="status"
      className="absolute bottom-7 left-5 right-5 z-[5] flex items-center gap-2 rounded-r2 bg-ink px-3.5 py-2.5 text-small text-bg shadow-sh2 @pc:bottom-8 @pc:left-auto @pc:right-8 @pc:w-[360px]"
    >
      <Icon name={icon} className="flex-none" />
      <span>{children}</span>
    </div>
  );
}
