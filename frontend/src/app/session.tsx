/* eslint-disable react-refresh/only-export-components -- 세션 컨텍스트 · 훅 · landingPath 를 한 곳에 둔다(LLD §1.2) */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { getMe, getToday, logout } from '@/api/endpoints';
import { ApiError, refreshSession, setOnUnauthenticated, type ApiErrorCode } from '@/api/http';
import { AppShell } from '@/components/AppShell';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { ShellActions } from '@/components/shellActions';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
import { clearOther } from '@/game/storage';
import { ServerClock } from './clock';

export type Today = Awaited<ReturnType<typeof getToday>>;
export type LandingPath = '/solo' | '/waiting' | '/quiz' | '/result';

/** 첫 화면(LLD §3.4). 「지금」은 서버 시각 — 기기 시계와 상관없다(W30). */
export function landingPath(today: Today, clock: ServerClock): LandingPath {
  if (today.group.members.length === 1) return '/solo';
  const now = clock.nowMs();
  if (now < Date.parse(today.startAt)) return '/waiting';
  if (now < Date.parse(today.deadlineAt)) return '/quiz';
  return '/result';
}

// ── 세션 컨텍스트 ──
// 「나」는 기억하지 않는다(SPEC W21 · prd D23): 앱을 열 때 · 새로고침 때 `GET /auth/me` 로 묻고,
// 로그인 직후에는 그 응답의 userId 를 쓴다. 로컬 스토리지에는 진행(gm:v1:*)만 있다.
export type TodayState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; today: Today }
  | { status: 'error'; code: ApiErrorCode | 'NETWORK' };

type SessionState =
  | { status: 'idle' } // 아직 모름 — 로그인 필요 경로에 오면 묻는다
  | { status: 'loading' }
  | { status: 'ready'; me: string; today: Today }
  | { status: 'out' } // 이 탭에서 로그아웃 · 세션 만료 — /login 으로
  | { status: 'error'; code: ApiErrorCode | 'NETWORK' };

interface SessionValue {
  /** 로그인 아이디(`/auth/me` · 로그인 응답). 세션을 다 불러오기 전에는 null */
  me: string | null;
  /** 이 탭에서 세션을 끝냈다 — 다시 묻지 않고 /login */
  ended: boolean;
  today: TodayState;
  clock: ServerClock;
  /** 로그인 응답의 userId 를 「나」로 둔다(저장하지 않는다) */
  signIn: (userId: string) => void;
  /** `/auth/me`(로그인 직후면 생략) → `GET /games/today` → clock.sync. quiet 면 지금 화면을 둔 채 다시 맞춘다 */
  loadSession: (opts?: { quiet?: boolean }) => Promise<void>;
  /** 로그아웃. false = 서버에 닿지 못해 로그인 상태를 그대로 둠 */
  signOut: () => Promise<boolean>;
}

const SessionContext = createContext<SessionValue | null>(null);

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('SessionProvider 밖에서 useSession 을 부름');
  return value;
}

export function SessionProvider({ clock, children }: { clock?: ServerClock; children: ReactNode }) {
  const navigate = useNavigate();
  const [ownClock] = useState(() => clock ?? new ServerClock());
  const [state, setState] = useState<SessionState>({ status: 'idle' });
  // 로그인 응답으로 이미 아는 나 — 다음 loadSession 한 번은 /auth/me 를 묻지 않는다
  const knownMe = useRef<string | null>(null);
  // 늦게 온 응답이 새 상태를 덮지 않게 — 로그인 · 로그아웃 · 새 조회마다 올린다
  const generation = useRef(0);
  // 마지막으로 불러온 나 — 다시 맞출 때 계정이 바뀌었는지 본다
  const readyMe = useRef<string | null>(null);

  const endLocal = useCallback(() => {
    generation.current++;
    knownMe.current = null;
    readyMe.current = null;
    setState({ status: 'out' });
    navigate('/login', { replace: true });
  }, [navigate]);

  // refresh 마저 401 → /login (SPEC W18 · W21 · §6.3)
  useEffect(() => {
    setOnUnauthenticated(endLocal);
    return () => setOnUnauthenticated(undefined);
  }, [endLocal]);

  const signIn = useCallback((userId: string) => {
    generation.current++;
    knownMe.current = userId;
    readyMe.current = null;
    setState({ status: 'idle' });
  }, []);

  const loadSession = useCallback(
    async ({ quiet = false }: { quiet?: boolean } = {}) => {
      const gen = ++generation.current;
      if (!quiet) setState({ status: 'loading' });
      try {
        const me = knownMe.current ?? (await getMe()).userId;
        const t = await getToday();
        if (gen !== generation.current) return;
        knownMe.current = null; // 다음에는(새로고침 · 다시 보일 때) 서버에 다시 묻는다
        ownClock.sync(t.serverTime);
        clearOther(me, t.date);
        const changed = readyMe.current !== null && readyMe.current !== me;
        readyMe.current = me;
        setState({ status: 'ready', me, today: t });
        // 다른 탭에서 다른 계정으로 로그인했다 — 쿠키가 그 계정이니 첫 화면부터
        if (changed) navigate('/', { replace: true });
      } catch (e) {
        if (gen !== generation.current) return;
        if (e instanceof ApiError && e.status === 401) {
          // refresh 까지 실패했다(onUnauthenticated 가 이미 불렸을 수 있다 — 다시 해도 같다)
          endLocal();
          return;
        }
        if (quiet) return;
        setState({ status: 'error', code: e instanceof ApiError ? e.code : 'NETWORK' });
      }
    },
    [ownClock, endLocal, navigate],
  );

  // 화면이 다시 보이면 나 · 서버 시각을 다시 맞춘다 — 기기가 잠들면 단조 시계가 멈추는 브라우저가 있고(W30),
  // 다른 탭에서 로그아웃 · 다른 계정 로그인을 했을 수 있다
  const ready = state.status === 'ready';
  useEffect(() => {
    if (!ready) return;
    const onVisible = () => {
      if (document.visibilityState === 'visible') void loadSession({ quiet: true });
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [ready, loadSession]);

  const signOut = useCallback(async () => {
    try {
      await logout();
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) return false;
      // access 가 만료됐다 — refresh 뒤 한 번 더(auth-signup SPEC §2.4). 서버 세션을 끝내야
      // 다음 로그인이 ALREADY_LOGGED_IN 에 막히지 않는다. refresh 도 401 이면 이미 끝난 세션이다.
      try {
        await refreshSession();
        await logout();
      } catch (e2) {
        if (!(e2 instanceof ApiError && e2.status === 401)) return false;
      }
    }
    endLocal();
    return true;
  }, [endLocal]);

  const value = useMemo<SessionValue>(() => {
    const today: TodayState =
      state.status === 'ready'
        ? { status: 'ready', today: state.today }
        : state.status === 'loading' || state.status === 'error'
          ? state
          : { status: 'idle' };
    return {
      me: state.status === 'ready' ? state.me : null,
      ended: state.status === 'out',
      today,
      clock: ownClock,
      signIn,
      loadSession,
      signOut,
    };
  }, [state, ownClock, signIn, loadSession, signOut]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

// ── 가드 · 첫 화면 ──
function SessionLoading() {
  return (
    <AppShell layout="center">
      <LoadingRegion>
        <SkeletonCard lines={4} />
      </LoadingRegion>
    </AppShell>
  );
}

function LogoutButton() {
  const { signOut } = useSession();
  const [pending, setPending] = useState(false);
  const [failed, setFailed] = useState(false);
  const onClick = async () => {
    setPending(true);
    setFailed(false);
    const ok = await signOut();
    if (!ok) {
      setPending(false);
      setFailed(true);
    }
  };
  return (
    <>
      {failed && (
        <span role="alert" className="mr-2 text-chip font-semibold text-bad">
          로그아웃하지 못했어요
        </span>
      )}
      <Button variant="secondary" size="sm" disabled={pending} onClick={onClick}>
        로그아웃
      </Button>
    </>
  );
}

/** 로그인 필요 경로. 「나」(`/auth/me`)와 오늘 게임 정보(서버 시각)가 있어야 지면을 그린다. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { me, ended, today, loadSession } = useSession();
  const idle = !me && !ended && today.status === 'idle';
  useEffect(() => {
    if (idle) void loadSession();
  }, [idle, loadSession]);

  // 이 탭에서 끝낸 세션이면 다시 묻지 않고 /login (뒤로 가기로 돌아와도 같다)
  if (ended) return <Navigate to="/login" replace />;
  if (today.status === 'error') {
    return (
      <AppShell
        layout="center"
        footer={
          <Button variant="secondary" onClick={() => void loadSession()}>
            다시 시도
          </Button>
        }
      >
        <Callout tone="bad" role="alert">
          잠시 뒤 다시 열어 주세요.
        </Callout>
      </AppShell>
    );
  }
  if (!me || today.status !== 'ready') return <SessionLoading />;
  return <ShellActions.Provider value={<LogoutButton />}>{children}</ShellActions.Provider>;
}

function LandingRedirect() {
  const { today, clock } = useSession();
  if (today.status !== 'ready') return null;
  return <Navigate to={landingPath(today.today, clock)} replace />;
}

/** `/` — 로그인돼 있으면(`/auth/me`) 첫 화면, 아니면 /login. */
export function Landing() {
  return (
    <RequireAuth>
      <LandingRedirect />
    </RequireAuth>
  );
}
