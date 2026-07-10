import type { DeliveryStatus } from '../types';

interface DeliveryBadgeProps {
  status: DeliveryStatus;
}

const labels: Record<DeliveryStatus, string> = {
  SENDING: '보내는 중',
  ACCEPTED: '큐 접수',
  PERSISTED: '저장됨',
  FAILED: '실패',
};

export function DeliveryBadge({ status }: DeliveryBadgeProps) {
  return (
    <span className={`delivery-badge delivery-${status.toLowerCase()}`}>
      {labels[status]}
    </span>
  );
}
