import containerQueries from '@tailwindcss/container-queries';
import type { Config } from 'tailwindcss';

// 디자인 기준(tokens.html)의 값을 그대로 옮긴다. 색은 src/styles/tokens.css 의 CSS 변수를 가리켜
// 라이트 · 다크(prefers-color-scheme)가 클래스 없이 바뀐다.
const color = (name: string) => `var(--${name})`;

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}', './.storybook/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: color('bg'),
        surface: { DEFAULT: color('surface'), 2: color('surface-2') },
        ink: { DEFAULT: color('ink'), 2: color('ink-2'), 3: color('ink-3') },
        line: { DEFAULT: color('line'), strong: color('line-strong') },
        accent: {
          DEFAULT: color('accent'),
          on: color('accent-on'),
          soft: color('accent-soft'),
          ink: color('accent-ink'),
        },
        ok: { DEFAULT: color('ok'), soft: color('ok-soft') },
        bad: { DEFAULT: color('bad'), soft: color('bad-soft') },
        ing: { DEFAULT: color('ing'), soft: color('ing-soft') },
        warn: { DEFAULT: color('warn'), soft: color('warn-soft') },
        scrim: color('scrim'),
      },
      fontFamily: {
        sans: [
          '-apple-system',
          'BlinkMacSystemFont',
          '"Apple SD Gothic Neo"',
          '"Pretendard"',
          '"Noto Sans KR"',
          '"Malgun Gothic"',
          'sans-serif',
        ],
        mono: ['ui-monospace', '"SF Mono"', 'Menlo', 'Consolas', 'monospace'],
      },
      // 타이포 — 모바일 값. PC 값은 컴포넌트에서 @pc: 로 준다.
      fontSize: {
        micro: ['11px', { lineHeight: '1.25' }],
        chip: ['12px', { lineHeight: '1.25' }],
        small: ['13px', { lineHeight: '1.5' }],
        body: ['15px', { lineHeight: '1.55' }],
        input: ['17px', { lineHeight: '1.3' }],
        question: ['20px', { lineHeight: '1.45' }],
        'question-pc': ['24px', { lineHeight: '1.45' }],
        title: ['24px', { lineHeight: '1.3' }],
        'title-l': ['28px', { lineHeight: '1.3' }],
        'title-l-pc': ['32px', { lineHeight: '1.3' }],
        'count-m': ['40px', { lineHeight: '1.05' }],
        count: ['56px', { lineHeight: '1.05' }],
        'count-pc': ['72px', { lineHeight: '1.05' }],
      },
      borderRadius: {
        r1: '8px',
        r2: '12px',
        r3: '16px',
        r4: '20px',
        pill: '999px',
      },
      boxShadow: {
        sh1: 'var(--sh1)',
        sh2: 'var(--sh2)',
      },
      maxWidth: {
        narrow: '456px',
        center: '640px',
        page: '1120px',
      },
      // 반응형 기준은 화면 폭이 아니라 앱 틀의 폭(컨테이너 쿼리 768) — tokens.html 「반응형」.
      containers: {
        pc: '768px',
      },
    },
  },
  plugins: [containerQueries],
} satisfies Config;
