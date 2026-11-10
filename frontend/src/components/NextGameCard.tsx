import { Card } from './Blocks';
import { Countdown } from './Countdown';

/** 다음 게임까지 남은 시간 카드(결과 · 1명 그룹 안내). */
export function NextGameCard({ seconds }: { seconds: number }) {
  return (
    <Card
      title="다음 게임까지"
      aside={<Countdown seconds={seconds} padHours label="다음 게임까지" />}
    >
      <p className="text-small text-ink-3">내일 07:00 시작</p>
    </Card>
  );
}
