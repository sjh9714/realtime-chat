import { useState, type FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { api, DEMO_MODE } from '../api';
import { useAuthStore } from '../stores/auth-store';

export function AuthScreen() {
  const [mode, setMode] = useState<'login' | 'signup'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const setSession = useAuthStore((state) => state.setSession);
  const mutation = useMutation({
    mutationFn: () =>
      mode === 'login'
        ? api.login(email, password)
        : api.signup(email, password, nickname),
    onSuccess: setSession,
  });
  const demoMutation = useMutation({
    mutationFn: () => api.login('alice@demo.local', 'demo-password'),
    onSuccess: setSession,
  });
  const error = mutation.error ?? demoMutation.error;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate();
  }

  function switchMode(nextMode: 'login' | 'signup') {
    setMode(nextMode);
    mutation.reset();
  }

  return (
    <main className="auth-shell" id="main-content">
      <section className="auth-intro" aria-labelledby="auth-heading">
        <p className="wordmark">Relay</p>
        <div className="auth-copy">
          <p className="eyebrow">Realtime delivery lab</p>
          <h1 id="auth-heading">보낸 순간과 저장된 순간을 구분합니다.</h1>
          <p>
            연결이 끊겨도 누락 메시지를 다시 맞추고, 각 메시지의 저장 상태를 직접 확인하는 채팅
            데모입니다.
          </p>
        </div>
        <ol className="delivery-sequence" aria-label="메시지 전달 단계">
          <li><span>01</span> SENDING</li>
          <li><span>02</span> ACCEPTED</li>
          <li><span>03</span> PERSISTED</li>
        </ol>
      </section>

      <section className="auth-panel" aria-label="계정 접속">
        <div className="auth-tabs" role="tablist" aria-label="로그인 방식">
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'login'}
            onClick={() => switchMode('login')}
          >
            로그인
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'signup'}
            onClick={() => switchMode('signup')}
          >
            계정 만들기
          </button>
        </div>
        <form onSubmit={submit} className="auth-form">
          {mode === 'signup' && (
            <label>
              닉네임
              <input
                name="nickname"
                autoComplete="nickname"
                value={nickname}
                onChange={(event) => setNickname(event.target.value)}
                maxLength={50}
                required
              />
            </label>
          )}
          <label>
            이메일
            <input
              name="email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
            />
          </label>
          <label>
            비밀번호
            <input
              name="password"
              type="password"
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              minLength={8}
              required
            />
          </label>
          <button className="primary-action" type="submit" disabled={mutation.isPending}>
            {mutation.isPending
              ? '확인 중…'
              : mode === 'login'
                ? '채팅으로 들어가기'
                : '계정 만들고 시작하기'}
          </button>
          {DEMO_MODE && (
            <button
              className="demo-action"
              type="button"
              disabled={demoMutation.isPending}
              onClick={() => demoMutation.mutate()}
            >
              {demoMutation.isPending ? '데모 준비 중…' : 'Alice 데모 계정으로 바로 시작'}
            </button>
          )}
          <p className="form-error" role="alert">
            {error instanceof Error ? error.message : ''}
          </p>
        </form>
      </section>
    </main>
  );
}
