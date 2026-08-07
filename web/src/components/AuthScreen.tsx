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
      {/*
        여기 있던 것: `Realtime delivery lab` 라벨, "보낸 순간과 저장된 순간을 구분합니다"
        표어, 그리고 `01 SENDING / 02 ACCEPTED / 03 PERSISTED` 3단계 목록.
        메신저 로그인 화면에 전달 파이프라인을 그려 두는 서비스는 없다.
        이름과 한 줄만 남기고 나머지는 양식에 자리를 내준다.
      */}
      <section className="auth-intro" aria-labelledby="auth-heading">
        <p className="wordmark">Relay</p>
        <div className="auth-copy">
          <h1 id="auth-heading">팀과 나누는 대화를 한곳에서.</h1>
        </div>
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
              {demoMutation.isPending ? '준비 중…' : '체험 계정으로 바로 시작'}
            </button>
          )}
          {/*
            공개 배포라 누구나 가입할 수 있다. 진짜 계정으로 오해하지 않도록 한 줄 남긴다.
            이건 제품 설명이 아니라 고지다 — 다만 눈에 띄지 않게 양식 아래에 둔다.
          */}
          {DEMO_MODE && <p className="auth-note">테스트 환경입니다. 대화 내용은 예고 없이 초기화될 수 있습니다.</p>}
          <p className="form-error" role="alert">
            {error instanceof Error ? error.message : ''}
          </p>
        </form>
      </section>
    </main>
  );
}
