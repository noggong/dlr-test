import { useContext, type ReactNode } from 'react';
import { cx } from '@/lib/cx';
import { Countdown } from './Countdown';
import { Icon } from './Icon';
import { ShellActions } from './shellActions';

/** narrow = 가입 · 로그인 456 · center = 대기 · 1명 안내 640 · split = 문제 풀이 · 완료 · 결과(본문 + 오른쪽 현황판 380) */
export type Layout = 'narrow' | 'center' | 'split';

export interface ClockProps {
  /** 지금 시각 등 고정 글(「06:55」 · 「마감」) */
  text?: string;
  /** 남은 초 — 주면 「마감까지 mm:ss」로 1초씩 줄인다 */
  deadlineSeconds?: number;
  tone?: 'normal' | 'hot' | 'end';
}

interface TopBarProps {
  layout: Layout;
  group?: { name: string; size: string };
  clock?: ClockProps;
}

/** 상단 바. 모바일은 그룹 · 시계 한 줄, PC 는 64 높이에 로고 · 그룹 · 시계. */
export function TopBar({ layout, group, clock }: TopBarProps) {
  const actions = useContext(ShellActions);
  return (
    <header
      className={cx(
        'flex min-h-[52px] items-center gap-3 px-5 pb-2.5 pt-[18px] text-small text-ink-2',
        '@pc:h-16 @pc:min-h-16 @pc:border-b @pc:border-line @pc:bg-surface @pc:px-8 @pc:py-0',
      )}
    >
      <span
        className={cx(
          'items-center gap-2 text-body font-extrabold text-accent-ink @pc:mr-4 @pc:inline-flex',
          layout === 'narrow' ? 'inline-flex' : 'hidden',
        )}
      >
        <i aria-hidden="true" className="inline-block h-[22px] w-[22px] rounded-[6px] bg-accent" />
        그룹 미션
      </span>
      {group && (
        <span
          className={cx(
            'mr-auto inline-flex items-center gap-1.5 text-body font-bold text-ink',
            layout !== 'narrow' && '@pc:border-l @pc:border-line @pc:pl-4',
          )}
        >
          {group.name}
          <small className="text-chip font-semibold text-ink-3">{group.size}</small>
        </span>
      )}
      {clock && <Clock {...clock} />}
      {actions && (
        <span className={cx('inline-flex items-center', !clock && 'ml-auto')}>{actions}</span>
      )}
    </header>
  );
}

function Clock({ text, deadlineSeconds, tone = 'normal' }: ClockProps) {
  return (
    <span
      className={cx(
        'num ml-auto inline-flex items-center gap-1.5 font-bold',
        tone === 'normal' && 'text-ink-2',
        tone === 'hot' && 'text-accent-ink',
        tone === 'end' && 'text-bad',
      )}
    >
      <Icon name="clock" />
      {deadlineSeconds !== undefined ? (
        <>
          마감까지 <Countdown seconds={deadlineSeconds} size="bare" label="마감까지" />
        </>
      ) : (
        text
      )}
    </span>
  );
}

interface AppShellProps extends TopBarProps {
  children: ReactNode;
  /** 아래 고정 영역(주 버튼) */
  footer?: ReactNode;
  /** 시트 · 대화상자 · 토스트 */
  overlay?: ReactNode;
}

/**
 * 앱 틀. 반응형 기준은 화면 폭이 아니라 이 틀의 폭(컨테이너 쿼리 768)이다.
 * split 틀에서 aside 로 표시된 덩어리(현황판)는 PC 에서 오른쪽 380 열로 간다.
 */
export function AppShell({ layout, group, clock, children, footer, overlay }: AppShellProps) {
  const width = cx(
    'mx-auto w-full @pc:px-8',
    layout === 'narrow' && '@pc:max-w-narrow',
    layout === 'center' && '@pc:max-w-center',
    layout === 'split' && '@pc:max-w-page',
  );
  return (
    <div className="relative flex h-dvh flex-col overflow-hidden bg-bg @container">
      <TopBar layout={layout} group={group} clock={clock} />
      <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">
        <main
          className={cx(
            width,
            'flex flex-1 flex-col gap-4 px-5 pt-1 @pc:gap-5 @pc:pt-8',
            layout === 'narrow' && '@pc:flex-none @pc:pt-14',
            layout === 'center' && '@pc:flex-none @pc:pt-12',
            layout === 'split' &&
              '@pc:grid @pc:grid-flow-row-dense @pc:grid-cols-[minmax(0,1fr)_380px] @pc:content-start @pc:items-start @pc:gap-x-8 @pc:gap-y-5 @pc:[&>*]:col-start-1 @pc:[&>[data-aside]]:col-start-2 @pc:[&>[data-aside]]:row-span-6 @pc:[&>[data-aside]]:row-start-1',
          )}
        >
          {children}
        </main>
        {footer && (
          <div
            className={cx(
              width,
              'grid gap-2 px-5 pb-7 pt-3 @pc:pb-8 @pc:pt-4',
              layout === 'center' && '@pc:[&>*]:min-w-[240px] @pc:[&>*]:justify-self-start',
            )}
          >
            {footer}
          </div>
        )}
      </div>
      {overlay}
    </div>
  );
}
