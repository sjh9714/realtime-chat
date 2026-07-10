export type RoomType = 'DIRECT' | 'GROUP';
export type MessageType = 'TEXT' | 'IMAGE' | 'FILE' | 'SYSTEM';
export type DeliveryStatus = 'SENDING' | 'ACCEPTED' | 'PERSISTED' | 'FAILED';
export type ConnectionStatus = 'CONNECTING' | 'ONLINE' | 'OFFLINE';

export interface AuthResponse {
  token: string;
  userId: number;
  email: string;
  nickname: string;
}

export interface UserSummary {
  id: number;
  nickname: string;
}

export interface RoomMember {
  userId: number;
  nickname: string;
  unreadCount: number;
}

export interface Room {
  id: number;
  name: string | null;
  displayName: string;
  type: RoomType;
  memberCount: number;
  unreadCount: number;
  lastMessageId: number | null;
  lastMessageContent: string | null;
  lastMessageSenderNickname: string | null;
  lastMessageAt: string | null;
  createdAt: string;
}

export interface RoomDetail {
  id: number;
  name: string | null;
  type: RoomType;
  createdBy: number;
  createdAt: string;
  members: RoomMember[];
}

export interface ChatMessage {
  id: number | null;
  messageKey: string | null;
  clientMessageId: string;
  roomId: number;
  senderId: number;
  senderNickname: string;
  content: string;
  type: MessageType;
  status: DeliveryStatus;
  createdAt: string;
  failureReason?: string;
}

export interface MessagePage {
  messages: ChatMessage[];
  hasMore: boolean;
  nextCursor: number | null;
}

export interface MessageSync {
  messages: ChatMessage[];
  hasMore: boolean;
  lastMessageId: number | null;
}

export interface PublishAck {
  clientMessageId: string;
  roomId: number;
  status: 'ACCEPTED';
  acceptedAt: string;
}

export interface PersistedAck {
  clientMessageId: string;
  messageKey: string;
  messageId: number;
  roomId: number;
  status: 'PERSISTED';
  persistedAt: string;
}

export interface PublishError {
  clientMessageId: string;
  roomId: number;
  status: 'FAILED';
  code: string;
  reason: string;
  failedAt: string;
}

export interface StompError {
  code: string;
  message: string;
  clientMessageId: string | null;
  roomId: number | null;
  timestamp: string;
}

export interface PresenceEvent {
  userId: number;
  status: 'ONLINE' | 'OFFLINE';
  timestamp: number;
}
