import type { Meta, StoryObj } from '@storybook/react';
import { Skeleton } from './Skeleton';

const meta: Meta<typeof Skeleton> = { title: '공용/Skeleton', component: Skeleton };
export default meta;

export const Lines: StoryObj<typeof Skeleton> = { args: { lines: 3 } };
export const Block: StoryObj<typeof Skeleton> = { args: { className: 'h-24' } };
