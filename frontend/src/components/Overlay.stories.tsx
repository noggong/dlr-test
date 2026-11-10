import type { Meta, StoryObj } from '@storybook/react';
import { Button } from './Button';
import { Modal, Sheet } from './Overlay';

const meta: Meta = {
  title: '공용/Sheet · Modal',
  decorators: [
    (S) => <div className="relative h-[640px] overflow-hidden border border-line">{S()}</div>,
  ],
};
export default meta;

export const PassSheet: StoryObj = {
  name: 'Sheet — 패스 확인(모바일 아래 · PC 가운데 440)',
  render: () => (
    <Sheet title="1번 문제를 패스할까요?" icon={{ name: 'pass', tone: 'acc' }}>
      <p className="text-ink-2">
        1번 그대로, <b>같은 난이도의 다른 문제</b>로 바꿔요. 진행도는 오르지 않아요.
      </p>
      <div className="grid grid-cols-2 gap-2">
        <Button variant="secondary">그대로 풀기</Button>
        <Button>패스하기</Button>
      </div>
    </Sheet>
  ),
};

export const WarnModal: StoryObj = {
  name: 'Modal — 부정행위 경고',
  render: () => (
    <Modal alert title="부정행위로 실패 처리될 수 있어요" icon={{ name: 'alert', tone: 'warn' }}>
      <p className="text-ink-2">저장된 풀이 상태가 지워지면 오늘 미션은 실패로 처리돼요.</p>
      <Button>계속 풀기</Button>
    </Modal>
  ),
};
