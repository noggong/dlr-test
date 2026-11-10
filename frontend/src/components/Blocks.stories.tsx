import type { Meta, StoryObj } from '@storybook/react';
import { Card, Hero, KeyValue, PeopleList } from './Blocks';
import { StatusChip } from './StatusChip';

const meta: Meta = { title: '바탕/Hero · Card · KeyValue · PeopleList' };
export default meta;

export const All: StoryObj = {
  render: () => (
    <div className="grid max-w-[390px] gap-4">
      <Hero
        eyebrow="매일 07:00 · 그룹 퀴즈 미션"
        title="가입하기"
        large
        sub="아이디를 만들고 함께할 그룹을 고르세요."
      />
      <Hero icon={{ name: 'octagon', tone: 'bad' }} title="오늘 미션은 실패 처리됐어요" />
      <Card title="어제 결과" aside={<StatusChip tone="ok">그룹 미션 성공</StatusChip>}>
        <p className="num text-small text-ink-3">내 순위 2위 / 5명 · 07:44:10 완료</p>
      </Card>
      <Card>
        <KeyValue
          items={[
            ['아이디', 'junho'],
            ['그룹', '아침7시클럽'],
          ]}
        />
      </Card>
      <Card title="그룹원">
        <PeopleList
          people={[{ id: 'junho', me: true }, { id: 'minji' }, { id: 'seoyun', later: true }]}
        />
      </Card>
    </div>
  ),
};
