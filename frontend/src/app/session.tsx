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
import { getToday, logout } from '@/api/endpoints';
import { ApiError, refreshSession, setOnUnauthenticated, type ApiErrorCode } from '@/api/http';
import { AppShell } from '@/components/AppShell';
import { Button } from '@/components/Button';
import { Callout } from '@/components/Callout';
import { ShellActions } from '@/components/shellActions';
import { LoadingRegion, SkeletonCard } from '@/components/Skeleton';
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

// ── 로컬 스토리지(SPEC W21): gm:me = 로그인 아이디, gm:v1:<userId> = 진행(LLD §2.2) ──
export const ME_KEY = 'gm:me';
const PROGRESS_PREFIX = 'gm:v1:';

// 사생활 모드 등으로 스토리지를 못 쓰면 「없음」으로 본다(LLD §4).
function readMe(): string | null {
  try {
    return localStorage.getItem(ME_KEY);
  } catch {
    return null;
  }
}

function writeMe(userId: string): void {
  try {
    localStorage.setItem(ME_KEY, userId);
  } catch {
    // 저장하지 못해도 이 탭에서는 상태로 이어 간다 — 새로고침하면 다시 로그인한다
  }
}

function removeMe(): void {
  try {
    localStorage.removeItem(ME_KEY);
  } catch {
    // 위와 같음
  }
}

/** 로그인 때 — 다른 유저의 진행 키를 지운다(SPEC §6.5). */
function clearOtherUsers(me: string): void {
  try {
    const stale: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key?.startsWith(PROGRESS_PREFIX) && key !== PROGRESS_PREFIX + me) stale.push(key);
    }
    stale.forEach((key) => localStorage.removeItem(key));
  } catch {
    // 위와 같음
  }
}

/** 게임 날짜를 알게 되면 — 날짜가 다른 내 진행 키를 지운다. 깨진 값은 storage 가 「빔」으로 본다. */
function clearStaleDate(me: string, date: string): void {
  try {
    const key = PROGRESS_PREFIX + me;
    const raw = localStorage.getItem(key);
    if (raw === null) return;
    const saved: unknown = JSON.parse(raw);
    const savedDate = (saved as { date?: unknown } | null)?.date;
    if (typeof savedDate === 'string' && savedDate !== date) localStorage.removeItem(key);
  } catch {
    // 깨진 JSON · 스토리지 접근 불가 — 그대로 둔다
  }
}

// ── 세션 컨텍스트 ──
export type TodayState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; today: Today }
  | { status: 'error'; code: ApiErrorCode | 'NETWORK' };

interface SessionValue {
  /** 로그인 아이디(gm:me). 없으면 로그인 안 됨 */
  me: string | null;
  /** 이 탭에서 로그아웃 · 세션 만료로 방금 끝냈다 — /login 으로 가는 중(다시 로그아웃하지 않는다) */
  ended: boolean;
  today: TodayState;
  clock: ServerClock;
  /** 로그인 · 가입 응답의 userId 를 「나」로 둔다 */
  signIn: (userId: string) => void;
  /** `GET /games/today` → clock.sync(serverTime). quiet 면 불러오는 동안 · 실패해도 지금 화면을 둔다 */
  loadToday: (opts?: { quiet?: boolean }) => Promise<void>;
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
  const [me, setMe] = useState<string | null>(readMe);
  const [ended, setEnded] = useState(false);
  const [today, setToday] = useState<TodayState>({ status: 'idle' });
  // 늦게 온 응답이 새 상태를 덮지 않게 — 로그인 · 로그아웃 · 새 조회마다 올린다
  const generation = useRef(0);

  const endLocal = useCallback(() => {
    generation.current++;
    removeMe();
    setMe(null);
    setEnded(true);
    setToday({ status: 'idle' });
    navigate('/login', { replace: true });
  }, [navigate]);

  // refresh 마저 401 → /login (SPEC W18 · §6.3)
  useEffect(() => {
    setOnUnauthenticated(endLocal);
    return () => setOnUnauthenticated(undefined);
  }, [endLocal]);

  const signIn = useCallback((userId: string) => {
    generation.current++;
    writeMe(userId);
    clearOtherUsers(userId);
    setMe(userId);
    setEnded(false);
    setToday({ status: 'idle' });
  }, []);

  const loadToday = useCallback(
    async ({ quiet = false }: { quiet?: boolean } = {}) => {
      const gen = ++generation.current;
      if (!quiet) setToday({ status: 'loading' });
      try {
        const t = await getToday();
        if (gen !== generation.current) return;
        ownClock.sync(t.serverTime);
        if (me) clearStaleDate(me, t.date);
        setToday({ status: 'ready', today: t });
      } catch (e) {
        if (gen !== generation.current) return;
        if (e instanceof ApiError && e.status === 401) {
          // refresh 까지 실패했다(onUnauthenticated 가 이미 불렸을 수 있다 — 다시 해도 같다)
          endLocal();
          return;
        }
        if (quiet) return;
        setToday({ status: 'error', code: e instanceof ApiError ? e.code : 'NETWORK' });
      }
    },
    [ownClock, me, endLocal],
  );

  // 기기가 잠들면 단조 시계가 멈추는 브라우저가 있다 — 화면이 다시 보이면 서버 시각을 다시 맞춘다(W30)
  const todayReady = today.status === 'ready';
  useEffect(() => {
    if (!todayReady) return;
    const onVisible = () => {
      if (document.visibilityState === 'visible') void loadToday({ quiet: true });
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [todayReady, loadToday]);

  // 다른 탭에서 로그아웃 · 다른 계정으로 로그인하면 쿠키가 바뀐다 — 이 탭도 따라간다
  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key !== ME_KEY && e.key !== null) return;
      const next = readMe();
      if (next === me) return;
      generation.current++;
      setMe(next);
      setEnded(next === null);
      setToday({ status: 'idle' });
      navigate(next === null ? '/login' : '/', { replace: true });
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [me, navigate]);

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

  const value = useMemo(
    () => ({ me, ended, today, clock: ownClock, signIn, loadToday, signOut }),
    [me, ended, today, ownClock, signIn, loadToday, signOut],
  );
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

/** `gm:me` 가 없다 — 남은 쿠키가 있을 수 있으니 로그아웃한 뒤 /login (ORACLE O20). */
function MissingMe() {
  const { signOut } = useSession();
  const navigate = useNavigate();
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void signOut().finally(() => navigate('/login', { replace: true }));
  }, [signOut, navigate]);
  return <SessionLoading />;
}

/** 로그인 필요 경로. 「나」와 오늘 게임 정보(서버 시각)가 있어야 지면을 그린다. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { me, ended, today, loadToday } = useSession();
  useEffect(() => {
    if (me && today.status === 'idle') void loadToday();
  }, [me, today.status, loadToday]);

  // 이 탭에서 끝낸 세션이면 로그아웃을 다시 보내지 않고 /login (뒤로 가기로 돌아와도 같다)
  if (!me) return ended ? <Navigate to="/login" replace /> : <MissingMe />;
  if (today.status === 'error') {
    return (
      <AppShell
        layout="center"
        footer={
          <Button variant="secondary" onClick={() => void loadToday()}>
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
  if (today.status !== 'ready') return <SessionLoading />;
  return <ShellActions.Provider value={<LogoutButton />}>{children}</ShellActions.Provider>;
}

function LandingRedirect() {
  const { today, clock } = useSession();
  if (today.status !== 'ready') return null;
  return <Navigate to={landingPath(today.today, clock)} replace />;
}

/** `/` — 로그인돼 있으면 첫 화면, 아니면 /login. */
export function Landing() {
  const { me } = useSession();
  if (!me) return <Navigate to="/login" replace />;
  return (
    <RequireAuth>
      <LandingRedirect />
    </RequireAuth>
  );
}
