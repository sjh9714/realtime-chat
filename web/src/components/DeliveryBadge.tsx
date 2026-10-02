import type { DeliveryStatus } from '../types';

interface DeliveryBadgeProps {
  status: DeliveryStatus;
}

// 저장 확인은 상대방의 수신·읽음 확인과 다르다.
const labels: Record<DeliveryStatus, string> = {
  SENDING: '보내는 중',
  ACCEPTED: '서버 접수',
  PERSISTED: '서버 저장 완료',
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
