import type { Remain } from '@/lib/time';
import { Card } from './Blocks';
import { Countdown } from './Countdown';

/** 다음 게임까지 남은 시간 카드(결과 · 1명 그룹 안내). `when` = 아래 줄(기본 「내일 07:00 시작」). */
export function NextGameCard({ when = '내일 07:00 시작', ...remain }: Remain & { when?: string }) {
  return (
    <Card title="다음 게임까지" aside={<Countdown {...remain} padHours label="다음 게임까지" />}>
      <p className="text-small text-ink-3">{when}</p>
    </Card>
  );
}
