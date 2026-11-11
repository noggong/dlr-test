import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, NetworkError } from '@/api/http';
import { openSse, SseParser, type SseFrame } from './sse';

function parseAll(chunks: string[]) {
  const frames: SseFrame[] = [];
  const comments: string[] = [];
  const p = new SseParser({ onFrame: (f) => frames.push(f), onComment: (c) => comments.push(c) });
  for (const c of chunks) p.feed(c);
  return { frames, comments };
}

const STREAM = [
  'id: 17\nevent: board.snapshot\ndata: {"version":17}\n\n',
  ': ping\n\n',
  'id: 18\r\nevent: member.progressed\r\ndata: {"a":\r\ndata: 1}\r\n\r\n',
  'data:no-space\n\n',
].join('');

const EXPECTED: SseFrame[] = [
  { event: 'board.snapshot', id: '17', data: '{"version":17}' },
  { event: 'member.progressed', id: '18', data: '{"a":\n1}' },
  { event: 'message', id: undefined, data: 'no-space' },
];

describe('SseParser (W26 · realtime-board §6)', () => {
  it('O28 event · id · 여러 줄 data · 빈 줄 경계 · \\r\\n', () => {
    const { frames, comments } = parseAll([STREAM]);
    expect(frames).toEqual(EXPECTED);
    expect(comments).toEqual(['ping']);
  });

  it('O28 청크가 어디서 끊겨도 같은 결과(한 글자씩 · \\r 과 \\n 사이 포함)', () => {
    expect(parseAll(STREAM.split(''))).toEqual(parseAll([STREAM]));
    for (let cut = 1; cut < STREAM.length; cut++) {
      expect(parseAll([STREAM.slice(0, cut), STREAM.slice(cut)]).frames).toEqual(EXPECTED);
    }
  });

  it('O28 `: ping` 은 이벤트가 아니라 하트비트 콜백', () => {
    const { frames, comments } = parseAll([': ping\n\n', ': ping\r\n\r\n']);
    expect(frames).toEqual([]);
    expect(comments).toEqual(['ping', 'ping']);
  });

  it('O28 빈 줄이 오기 전에는 내보내지 않고, data 없는 프레임은 버린다', () => {
    expect(parseAll(['event: x\ndata: 1\n']).frames).toEqual([]);
    expect(parseAll(['event: x\nid: 3\n\n']).frames).toEqual([]);
  });

  it('O28 \\r 만으로 끝나는 줄도 줄 끝', () => {
    expect(parseAll(['event: a\rdata: 1\r\r']).frames).toEqual([
      { event: 'a', id: undefined, data: '1' },
    ]);
  });
});

function sseResponse(text: string) {
  return new Response(new TextEncoder().encode(text), {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream; charset=utf-8' },
  });
}

describe('openSse', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('O28 바이트 스트림을 읽어 프레임으로 — 다 읽으면 resolve', async () => {
    const fetchImpl = vi.fn(async () => sseResponse(STREAM));
    const stream = await openSse('/realtime/x', new AbortController().signal, fetchImpl);
    const frames: SseFrame[] = [];
    await stream.read({ onFrame: (f) => frames.push(f) });
    expect(frames).toEqual(EXPECTED);
    expect(fetchImpl).toHaveBeenCalledWith(
      '/realtime/x',
      expect.objectContaining({ headers: { Accept: 'text/event-stream' } }),
    );
  });

  it('O31 2xx 가 아니면 본문을 ApiError{status, code} 로', async () => {
    const fetchImpl = async () =>
      new Response(JSON.stringify({ code: 'UNAVAILABLE', message: 'x' }), { status: 503 });
    const err = await openSse('/r', new AbortController().signal, fetchImpl).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 503, code: 'UNAVAILABLE' });
  });

  it('O31 JSON 이 아닌 오류 · 스트림이 아닌 2xx 는 HTTP_<상태>, 연결 실패는 NetworkError', async () => {
    const html = async () => new Response('<html/>', { status: 502 });
    await expect(openSse('/r', new AbortController().signal, html)).rejects.toMatchObject({
      code: 'HTTP_502',
    });
    const spa = async () =>
      new Response('<html/>', { status: 200, headers: { 'Content-Type': 'text/html' } });
    await expect(openSse('/r', new AbortController().signal, spa)).rejects.toMatchObject({
      code: 'HTTP_200',
    });
    const down = async (): Promise<Response> => {
      throw new TypeError('Failed to fetch');
    };
    await expect(openSse('/r', new AbortController().signal, down)).rejects.toBeInstanceOf(
      NetworkError,
    );
  });

  it('O30 abort 하면 읽기가 signal.reason 으로 끝난다(본문이 열려 있어도)', async () => {
    const body = new ReadableStream<Uint8Array>({ start: () => undefined }); // 영원히 안 온다
    const fetchImpl = async () =>
      new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
    const controller = new AbortController();
    const stream = await openSse('/r', controller.signal, fetchImpl);
    const reading = stream.read({ onFrame: () => undefined });
    const reason = new Error('stop');
    controller.abort(reason);
    await expect(reading).rejects.toBe(reason);
  });
});
