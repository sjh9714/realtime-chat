/**
 * 초대 링크.
 *
 * 라우터를 들이지 않는다 — 이 앱은 화면이 하나이고, 그것 하나 때문에 의존성을 늘릴 이유가 없다.
 * 대신 쿼리 문자열을 쓴다: `https://…/?join=12`
 *
 * 링크를 연 사람이 로그인돼 있으면 바로 참여시키고, 아니면 로그인한 뒤에 참여시킨다.
 * 그래서 값을 한 번 읽어 두고 주소창에서는 지운다 — 새로고침 때마다 다시 참여를 시도하면
 * 이미 참여 중이라는 응답만 반복된다.
 */
const PARAM = 'join';

let pendingRoomId: number | null = readFromUrl();

function readFromUrl(): number | null {
  if (typeof window === 'undefined') return null;
  const raw = new URLSearchParams(window.location.search).get(PARAM);
  if (!raw) return null;
  const roomId = Number(raw);
  return Number.isSafeInteger(roomId) && roomId > 0 ? roomId : null;
}

/** 링크를 타고 들어왔는가. 로그인 화면이 안내를 띄울지 정할 때 쓴다 */
export function hasPendingInvite(): boolean {
  return pendingRoomId !== null;
}

/** 한 번만 꺼내 쓴다. 꺼내는 순간 주소창에서도 지운다 */
export function takePendingInvite(): number | null {
  const roomId = pendingRoomId;
  pendingRoomId = null;
  if (roomId !== null && typeof window !== 'undefined') {
    const url = new URL(window.location.href);
    url.searchParams.delete(PARAM);
    window.history.replaceState(null, '', url.toString());
  }
  return roomId;
}

/** 그룹 대화 머리글의 '초대 링크 복사'가 만드는 주소 */
export function inviteUrl(roomId: number): string {
  const url = new URL(window.location.href);
  url.search = '';
  url.hash = '';
  url.searchParams.set(PARAM, String(roomId));
  return url.toString();
}
