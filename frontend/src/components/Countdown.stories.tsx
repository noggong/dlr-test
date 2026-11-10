import type { Meta, StoryObj } from '@storybook/react';
import { Countdown } from './Countdown';

const meta: Meta<typeof Countdown> = { title: '공용/Countdown', component: Countdown };
export default meta;
type Story = StoryObj<typeof Countdown>;

export const HeroMinutes: Story = { args: { seconds: 299, size: 'hero' } };
export const HeroHours: Story = { args: { seconds: 4 * 3600 + 49 * 60 + 59, size: 'hero' } };
export const Inline: Story = { args: { seconds: 22 * 3600 + 47 * 60 + 30, padHours: true } };
