import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';
import { useCallback, useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ZodType } from 'zod';
import { api, WS_URL } from '../api';
import {
  chatMessageSchema,
  persistedAckSchema,
  presenceEventSchema,
  publishAckSchema,
  publishErrorSchema,
  stompErrorSchema,
} from '../schemas';
import { lastPersistedMessageId, useChatStore } from '../stores/chat-store';
import type { ChatMessage, UserSummary } from '../types';

interface UseChatSocketOptions {
  token: string;
  currentUser: UserSummary;
  selectedRoomId: number | null;
}

interface SendMessageOptions {
  content: string;
  clientMessageId?: string;
}

function parseFrame<T>(frame: IMessage, schema: ZodType<T>): T | null {
  try {
    const result = schema.safeParse(JSON.parse(frame.body) as unknown);
    if (result.success) return result.data;
  } catch {
    // Invalid frames are reported below without crashing the STOMP callback.
  }
  useChatStore
    .getState()
    .setConnectionNotice('서버 응답 형식이 올바르지 않아 해당 이벤트를 무시했습니다.');
  return null;
}

export function useChatSocket({
  token,
  currentUser,
  selectedRoomId,
}: UseChatSocketOptions) {
  const queryClient = useQueryClient();
  const clientRef = useRef<Client | null>(null);
  const roomSubscriptionsRef = useRef<StompSubscription[]>([]);
  const selectedRoomRef = useRef(selectedRoomId);
  const heartbeatRef = useRef<number | null>(null);

  const syncRoom = useCallback(
    async (roomId: number) => {
      const store = useChatStore.getState();
      const existing = store.messagesByRoom[roomId] ?? [];
      const afterMessageId = lastPersistedMessageId(existing);
      try {
        if (afterMessageId === undefined && existing.length === 0) {
          const page = await api.messages(token, roomId);
          store.mergePersistedMessages(roomId, page.messages);
        } else {
          let cursor = afterMessageId;
          let hasMore = true;
          while (hasMore) {
            const response = await api.syncMessages(token, roomId, cursor);
            store.mergePersistedMessages(roomId, response.messages);
            cursor = response.lastMessageId ?? cursor;
            hasMore = response.hasMore && response.messages.length > 0;
          }
        }
        const online = await api.onlineMembers(token, roomId);
        store.setOnlineMembers(roomId, online);
        store.setConnectionNotice(null);
      } catch (error) {
        store.setConnectionNotice(
          error instanceof Error ? error.message : '누락 메시지를 동기화하지 못했습니다.',
        );
      }
    },
    [token],
  );

  const unsubscribeRoom = useCallback(() => {
    roomSubscriptionsRef.current.forEach((subscription) => subscription.unsubscribe());
    roomSubscriptionsRef.current = [];
  }, []);

  const subscribeRoom = useCallback(
    (roomId: number) => {
      const client = clientRef.current;
      if (!client?.connected) return;
      unsubscribeRoom();
      roomSubscriptionsRef.current = [
        client.subscribe(`/topic/room.${roomId}`, (frame) => {
          const message = parseFrame(frame, chatMessageSchema);
          if (!message) return;
          useChatStore.getState().applyPersistedMessage(message);
          void queryClient.invalidateQueries({ queryKey: ['rooms'] });
        }),
        client.subscribe(`/topic/room.${roomId}.presence`, (frame) => {
          const event = parseFrame(frame, presenceEventSchema);
          if (!event) return;
          useChatStore
            .getState()
            .applyPresence(roomId, event.userId, event.status === 'ONLINE');
        }),
      ];
      void syncRoom(roomId);
    },
    [queryClient, syncRoom, unsubscribeRoom],
  );

  useEffect(() => {
    selectedRoomRef.current = selectedRoomId;
    if (selectedRoomId !== null && clientRef.current?.connected) {
      subscribeRoom(selectedRoomId);
    } else if (selectedRoomId === null) {
      unsubscribeRoom();
    }
  }, [selectedRoomId, subscribeRoom, unsubscribeRoom]);

  useEffect(() => {
    const store = useChatStore.getState();
    store.setConnectionStatus('CONNECTING');

    const client = new Client({
      brokerURL: WS_URL,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 1_500,
      heartbeatIncoming: 20_000,
      heartbeatOutgoing: 20_000,
      connectionTimeout: 8_000,
      onConnect: () => {
        const current = useChatStore.getState();
        current.setConnectionStatus('ONLINE');
        current.setConnectionNotice('연결이 복구되었습니다. 누락 메시지를 확인하고 있습니다.');

        client.subscribe('/user/queue/messages/ack', (frame) => {
          const ack = parseFrame(frame, publishAckSchema);
          if (ack) current.applyAccepted(ack);
        });
        client.subscribe('/user/queue/messages/persisted', (frame) => {
          const ack = parseFrame(frame, persistedAckSchema);
          if (!ack) return;
          current.applyPersistedAck(ack);
          void queryClient.invalidateQueries({ queryKey: ['rooms'] });
        });
        client.subscribe('/user/queue/messages/error', (frame) => {
          const error = parseFrame(frame, publishErrorSchema);
          if (error) current.applyFailed(error);
        });
        client.subscribe('/user/queue/errors', (frame) => {
          const error = parseFrame(frame, stompErrorSchema);
          if (!error) return;
          if (error.clientMessageId && error.roomId) {
            current.applyFailed({
              clientMessageId: error.clientMessageId,
              roomId: error.roomId,
              status: 'FAILED',
              code: error.code,
              reason: error.message,
              failedAt: error.timestamp,
            });
          }
          current.setConnectionNotice(error.message);
        });

        const roomId = selectedRoomRef.current;
        if (roomId !== null) subscribeRoom(roomId);
        heartbeatRef.current = window.setInterval(() => {
          if (client.connected) {
            client.publish({ destination: '/app/presence.heartbeat', body: '{}' });
          }
        }, 25_000);
      },
      onWebSocketClose: () => {
        roomSubscriptionsRef.current = [];
        useChatStore.getState().setConnectionStatus('OFFLINE');
        useChatStore
          .getState()
          .setConnectionNotice('연결이 끊겼습니다. 전송을 멈추고 자동 재연결 중입니다.');
        if (heartbeatRef.current !== null) window.clearInterval(heartbeatRef.current);
      },
      onStompError: (frame) => {
        useChatStore
          .getState()
          .setConnectionNotice(frame.headers.message ?? '실시간 연결에서 오류가 발생했습니다.');
      },
    });
    clientRef.current = client;
    client.activate();

    return () => {
      unsubscribeRoom();
      if (heartbeatRef.current !== null) window.clearInterval(heartbeatRef.current);
      clientRef.current = null;
      void client.deactivate();
      store.setConnectionStatus('OFFLINE');
    };
  }, [queryClient, subscribeRoom, token, unsubscribeRoom]);

  const sendMessage = useCallback(
    ({ content, clientMessageId = crypto.randomUUID() }: SendMessageOptions) => {
      if (selectedRoomId === null) return;
      const optimistic: ChatMessage = {
        id: null,
        messageKey: null,
        clientMessageId,
        roomId: selectedRoomId,
        senderId: currentUser.id,
        senderNickname: currentUser.nickname,
        content,
        type: 'TEXT',
        status: 'SENDING',
        createdAt: new Date().toISOString(),
      };
      const store = useChatStore.getState();
      store.addOptimisticMessage(optimistic);

      const client = clientRef.current;
      if (!client?.connected) {
        store.applyFailed({
          clientMessageId,
          roomId: selectedRoomId,
          status: 'FAILED',
          code: 'SOCKET_OFFLINE',
          reason: '연결이 복구된 뒤 다시 보내 주세요.',
          failedAt: new Date().toISOString(),
        });
        return;
      }
      client.publish({
        destination: '/app/chat.send',
        body: JSON.stringify({
          clientMessageId,
          roomId: selectedRoomId,
          content,
          type: 'TEXT',
        }),
      });
    },
    [currentUser.id, currentUser.nickname, selectedRoomId],
  );

  return { sendMessage, syncRoom };
}
