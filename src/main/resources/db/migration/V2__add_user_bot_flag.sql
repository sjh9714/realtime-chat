-- 계정이 봇인지 표시한다.
--
-- 화면이 이름 옆에 BOT 배지를 그리려면 이 사실을 알아야 한다. 닉네임을 문자열로 비교하는
-- 꼼수를 쓰지 않으려고 컬럼으로 둔다 — Slack·Discord도 is_bot을 핵심 모델에 갖고 있다.
--
-- 컬럼은 제품의 사실이고, `안내봇`이라는 인물과 그 응답 규칙은 데모다(demo 프로파일).
ALTER TABLE users ADD COLUMN IF NOT EXISTS bot BOOLEAN NOT NULL DEFAULT FALSE;
