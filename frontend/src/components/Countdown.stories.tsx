import type { Meta, StoryObj } from '@storybook/react';
import { Countdown } from './Countdown';

const meta: Meta<typeof Countdown> = { title: '공용/Countdown', component: Countdown };
export default meta;
type Story = StoryObj<typeof Countdown>;

export const HeroMinutes: Story = { args: { seconds: 299, size: 'hero' } };
export const HeroHours: Story = { args: { seconds: 4 * 3600 + 49 * 60 + 59, size: 'hero' } };
export const Inline: Story = { args: { seconds: 22 * 3600 + 47 * 60 + 30, padHours: true } };

/** 서버 기준 시각까지(`until` + `now` — 화면은 `clock.nowMs`) — 여기서는 열 때부터 5분 고정 시계 */
const fixedNow = 1_700_000_000_000;
export const Until: Story = {
  args: { until: fixedNow + 300_000, now: () => fixedNow, size: 'hero' },
};
