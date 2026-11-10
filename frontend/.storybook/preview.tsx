import type { Preview } from '@storybook/react';
import { MemoryRouter } from 'react-router-dom';
import '../src/styles/index.css';

const preview: Preview = {
  decorators: [
    (Story) => (
      <MemoryRouter>
        {/* 컴포넌트의 PC 값(@pc:)은 앱 틀 폭 기준 — 스토리도 같은 컨테이너 안에서 본다 */}
        <div className="relative @container">
          <Story />
        </div>
      </MemoryRouter>
    ),
  ],
  parameters: {
    layout: 'padded',
    backgrounds: { disable: true },
    viewport: {
      viewports: {
        m360: { name: '모바일 360', styles: { width: '360px', height: '780px' } },
        m390: { name: '모바일 390', styles: { width: '390px', height: '844px' } },
        t768: { name: '경계 768', styles: { width: '768px', height: '1024px' } },
        pc1280: { name: 'PC 1280', styles: { width: '1280px', height: '800px' } },
      },
    },
  },
};

export default preview;
