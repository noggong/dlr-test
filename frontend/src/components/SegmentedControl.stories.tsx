import type { Meta, StoryObj } from '@storybook/react';
import { useState } from 'react';
import { SegmentedControl } from './SegmentedControl';

const meta: Meta<typeof SegmentedControl> = { title: '공용/SegmentedControl' };
export default meta;

function Demo() {
  const [v, setV] = useState<'new' | 'join'>('new');
  return (
    <div className="max-w-[390px]">
      <SegmentedControl
        label="그룹 고르기"
        value={v}
        onChange={setV}
        options={[
          { value: 'new', label: '새 그룹 만들기' },
          { value: 'join', label: '기존 그룹 들어가기' },
        ]}
      />
    </div>
  );
}

export const GroupMode: StoryObj = { render: () => <Demo /> };
