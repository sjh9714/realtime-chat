import type { DeliveryStatus } from '../types';

interface DeliveryBadgeProps {
  status: DeliveryStatus;
}

/**
 * 이 앱의 주장이 여기 있다 — "화면에 보였다"와 "실제 저장됐다"는 다르다.
 *
 * 점과 글자를 함께 둔다. 색만으로 말하면 색각 이상이 있는 사람에게는 아무 정보가 아니고,
 * 점이 작아 안 보이는 화면에서도 글자가 남는다(DESIGN.md 접근성).
 *
 * 점 자체는 `aria-hidden`이다. 화면 낭독기에는 글자만 한 번 읽히면 된다.
 */
const labels: Record<DeliveryStatus, string> = {
  SENDING: '보내는 중',
  ACCEPTED: '큐 접수',
  PERSISTED: '저장됨',
  FAILED: '실패',
};

export function DeliveryBadge({ status }: DeliveryBadgeProps) {
  return (
    <span className={`delivery-badge delivery-${status.toLowerCase()}`}>
      <i aria-hidden="true" />
      {labels[status]}
    </span>
  );
}
