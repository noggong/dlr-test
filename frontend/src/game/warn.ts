import { useEffect, useRef } from 'react';

// 부정행위 경고(SPEC W24 · prd R-F3) — 브라우저 닫기 · 탭 이동에 경고만 준다. 실패 처리는 하지 않는다:
// 실패는 로컬 스토리지가 빈 채로 다시 들어왔을 때 서버에 알리는 것뿐이다(W22).

/**
 * `beforeunload` 로 브라우저 확인 창을 띄우고, 화면이 숨었다가(hidden) 다시 보이면 `onReturn` 을 부른다.
 * 돌려준 함수로 해제한다.
 */
export function installWarning(onReturn: () => void): () => void {
  let hidden = document.visibilityState === 'hidden';
  const onBeforeUnload = (e: BeforeUnloadEvent) => {
    e.preventDefault();
    // 오래된 브라우저는 returnValue 가 있어야 확인 창을 띄운다
    e.returnValue = '';
  };
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') {
      hidden = true;
    } else if (hidden) {
      hidden = false;
      onReturn();
    }
  };
  window.addEventListener('beforeunload', onBeforeUnload);
  document.addEventListener('visibilitychange', onVisibility);
  return () => {
    window.removeEventListener('beforeunload', onBeforeUnload);
    document.removeEventListener('visibilitychange', onVisibility);
  };
}

/** 미션 중(07:00 ~ 08:00 · ① 통과 ~ `done`)에만 경고를 건다. `active` 가 거짓이 되면 바로 푼다. */
export function useMissionWarning(active: boolean, onReturn: () => void): void {
  const latest = useRef(onReturn);
  latest.current = onReturn;
  useEffect(() => {
    if (!active) return;
    return installWarning(() => latest.current());
  }, [active]);
}
