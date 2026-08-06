/**
 * README에 넣을 제품 화면을 찍는다.
 *
 * 왜 스크립트로 두는가: README의 화면은 `DocumentationGuardTest`가 존재를 강제한다.
 * 화면이 바뀌면 다시 찍어야 하는데, 손으로 찍으면 매번 조건이 달라져 재현되지 않는다.
 * 여기서 조건을 고정한다 — 두 사용자, 실제 대화, 실제 데모 스택.
 *
 * 보내는 쪽 화면을 찍는다. 이 앱의 주장이 전달 배지에 있는데(보였다 vs 저장됐다)
 * 배지는 자기가 보낸 메시지에만 붙는다. 받는 쪽 화면에는 그 주장이 보이지 않는다.
 *
 * 실행:
 *   docker compose -f docker-compose.demo.yml up --detach --wait
 *   (cd web && node scripts/capture-readme-screen.mjs)
 */
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const API = process.env.CAPTURE_API_URL ?? "http://localhost:18080";
const WEB = process.env.CAPTURE_WEB_URL ?? "http://localhost:14173";
// 실행 위치가 아니라 스크립트 위치를 기준으로 잡는다. cwd 기준으로 두면
// web/에서 실행할 때 web/docs/ 아래에 떨어진다. 실제로 한 번 그랬다.
//
// 스크립트가 web/ 아래 있는 이유: node는 import를 스크립트 파일 위치에서 해석하는데
// @playwright/test가 web/node_modules에만 있다. 저장소 루트에 두면 못 찾는다.
const OUT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../docs/assets/screens",
);
const VIEWPORT = { width: 1280, height: 720 };

async function signup(label) {
  const suffix = `${label}-${process.pid}-${Math.random().toString(16).slice(2, 6)}`;
  const res = await fetch(`${API}/api/auth/signup`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      email: `${suffix}@example.com`,
      password: "capture-only-password",
      nickname: `${label}${suffix.slice(-4)}`,
    }),
  });
  if (!res.ok) throw new Error(`signup ${label}: HTTP ${res.status}`);
  return res.json();
}

/** 세션을 sessionStorage에 심는다 (web/e2e와 같은 방식) */
async function pageAs(browser, session) {
  const context = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 2 });
  await context.addInitScript((value) => {
    sessionStorage.setItem("relay-auth", JSON.stringify({ state: { session: value }, version: 0 }));
  }, session);
  return context.newPage();
}

const browser = await chromium.launch();
try {
  const [alice, bob] = [await signup("Alice"), await signup("Bob")];
  const aPage = await pageAs(browser, alice);
  const bPage = await pageAs(browser, bob);

  await aPage.goto(WEB);
  await aPage.getByRole("searchbox", { name: "새 대화" }).fill(bob.nickname);
  await aPage.getByRole("button", { name: bob.nickname, exact: true }).click();
  await aPage.getByRole("heading", { name: bob.nickname }).waitFor();

  await bPage.goto(WEB);
  await bPage.getByRole("heading", { name: alice.nickname }).waitFor();

  const say = async (page, text) => {
    const box = page.getByRole("textbox").last();
    await box.fill(text);
    await box.press("Enter");
    await page.waitForTimeout(700);
  };

  await say(aPage, "내일 회의 자료 초안 올려뒀어요");
  await say(bPage, "확인했어요. 3장만 다시 볼게요");
  await say(aPage, "네, 그 부분만 고치면 될 것 같아요");

  // 배지가 ACCEPTED에서 PERSISTED로 넘어갈 때까지 기다린다.
  // 넘어가기 전에 찍으면 이 앱이 말하려는 것과 반대의 화면이 남는다.
  await aPage.getByText("저장됨").last().waitFor();
  await aPage.waitForTimeout(400);

  await mkdir(OUT, { recursive: true });
  const out = path.join(OUT, "conversation.png");
  await aPage.screenshot({ path: out, animations: "disabled" });
  console.log(`saved ${path.relative(process.cwd(), out)}`);
} finally {
  await browser.close();
}
