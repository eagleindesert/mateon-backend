#!/usr/bin/env node
// launch.js — 유저 A·B 의 실시간 채팅을 콘솔 창 2개로 띄운다.
// PowerShell 판(../../for-api-server/parallel-chat/launch.ps1)과 흐름은 동일하다:
//   1) .env 의 유저 A(MATEON_TEST_*) / 유저 B(MATEON_USERB_*) 로 로그인
//      (계정이 아직 없다면 이 스크립트는 만들어주지 않는다 — 먼저 ../../for-api-server/auth/02_auth.ps1 로 준비)
//   2) A 토큰으로 A<->B DM 방 조회/생성 (멱등) -> roomId 확보
//   3) chat-client.js 를 새 콘솔 창 2개로 실행 (A=cyan, B=green)
//
// 자격증명은 .env 에서만 읽고, 커맨드라인/새 창 실행 인자에는 절대 노출하지 않는다.
// 대신 임시 JSON 파일 하나에 담아 넘기고, chat-client.js 가 읽는 즉시 파일을 지운다.
//
// 사용법:
//   node launch.js

"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { spawn } = require("child_process");

// ----------------------------------------------------------------------------
// 0) .env 로드 — 이 폴더(websocket-javascript) 자체 .env 를 쓴다 (scripts/test/debug/oauth/.env
//    와 같은 패턴). for-api-server 폴더 밖으로 옮겨져도 동작하도록 상위 폴더를 거슬러 올라가지
//    않는다 — for-api-server/.env 와 값이 달라지면 이 폴더의 .env 를 직접 갱신해야 한다.
// ----------------------------------------------------------------------------
function loadDotEnv(filePath) {
    if (!fs.existsSync(filePath)) return;
    for (const rawLine of fs.readFileSync(filePath, "utf8").split(/\r?\n/)) {
        const line = rawLine.trim();
        if (!line || line.startsWith("#")) continue;
        const idx = line.indexOf("=");
        if (idx < 0) continue;
        const key = line.slice(0, idx).trim();
        const value = line.slice(idx + 1).trim();
        if (key) process.env[key] = value;
    }
}
loadDotEnv(path.join(__dirname, ".env"));

const baseUrl = process.env.MATEON_BASE_URL || "http://localhost:8080";
const userA = { email: process.env.MATEON_TEST_EMAIL, password: process.env.MATEON_TEST_PASSWORD };
const userB = { email: process.env.MATEON_USERB_EMAIL, password: process.env.MATEON_USERB_PASSWORD };

if (!userA.email || !userA.password || !userB.email || !userB.password) {
    console.error("(!) .env 에 MATEON_TEST_EMAIL/PASSWORD 와 MATEON_USERB_EMAIL/PASSWORD 가 모두 필요합니다.");
    process.exit(1);
}

// ----------------------------------------------------------------------------
// 1) REST 헬퍼
// ----------------------------------------------------------------------------
async function login(email, password) {
    const res = await fetch(`${baseUrl}/api/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
    });
    const json = await res.json().catch(() => null);
    if (!res.ok || !json?.data?.accessToken) return null;
    return json.data.accessToken;
}

function decodeJwtSubject(token) {
    const payload = token.split(".")[1];
    const normalized = payload.replace(/-/g, "+").replace(/_/g, "/");
    const json = Buffer.from(normalized, "base64").toString("utf8");
    return JSON.parse(json).sub;
}

async function ensureDmRoom(token, targetUserId) {
    const res = await fetch(`${baseUrl}/api/chat/rooms/dm`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ targetUserId: Number(targetUserId) }),
    });
    const json = await res.json().catch(() => null);
    if (!res.ok || !json?.data?.roomId) {
        throw new Error(`DM 방 준비 실패 (${res.status}): ${JSON.stringify(json)}`);
    }
    return json.data.roomId;
}

// ----------------------------------------------------------------------------
// 2) 창 하나 띄우기: 자격증명은 임시 JSON 파일로만 전달 (커맨드라인 노출 방지)
// ----------------------------------------------------------------------------
const clientPath = path.join(__dirname, "chat-client.js");

function startChatWindow({ label, email, password, roomId, color }) {
    const configPath = path.join(os.tmpdir(), `mateon-chat-${label}-${crypto.randomUUID()}.json`);
    fs.writeFileSync(configPath, JSON.stringify({ label, email, password, roomId, baseUrl, color }), {
        mode: 0o600,
    });

    if (process.platform === "win32") {
        // cmd.exe 의 내장 명령 start 로 새 콘솔 창을 띄운다. windowsVerbatimArguments 로
        // Node 의 자동 따옴표 처리를 끄고 cmd.exe 가 기대하는 문법을 직접 만든다.
        // 창 제목은 빈 문자열("")로 둔다 — 안 그러면 start 가 뒤따르는 첫 인자를 제목으로
        // 오인할 수 있다. 실제 창 제목은 chat-client.js 가 실행 후 process.title 로 설정한다.
        // "cmd /k" 로 감싸서 node 가 종료(정상 /quit 이든 로그인 실패든)된 뒤에도 창이 바로
        // 닫히지 않고 결과를 볼 수 있게 한다(PowerShell 판의 Start-Process -NoExit 과 동일 취지).
        const cmdLine = `/c start "" cmd /k node "${clientPath}" --config "${configPath}"`;
        spawn("cmd.exe", [cmdLine], {
            detached: true,
            stdio: "ignore",
            windowsVerbatimArguments: true,
        }).unref();
    } else {
        // macOS/Linux 는 터미널 자동 실행 방식이 배포판마다 달라 여기서는 지원하지 않는다.
        console.error(`(!) ${process.platform} 에서는 새 터미널 창 자동 실행을 지원하지 않습니다.`);
        console.error(`    다음을 직접 다른 터미널에서 실행하세요:`);
        console.error(`    node "${clientPath}" --config "${configPath}"`);
    }
}

// ----------------------------------------------------------------------------
// 3) 메인 흐름
// ----------------------------------------------------------------------------
async function main() {
    console.log("\n########## websocket-javascript launcher — 두 창 실시간 채팅 ##########");

    console.log(`\n[1] 유저 A 로그인: ${userA.email}`);
    const tokenA = await login(userA.email, userA.password);
    console.log(`[1] 유저 B 로그인: ${userB.email}`);
    const tokenB = await login(userB.email, userB.password);

    if (!tokenA || !tokenB) {
        console.error("\n(!) 로그인 실패 — 계정이 아직 없다면 ../../for-api-server/auth/02_auth.ps1 로 유저 A/B 를 먼저 만드세요.");
        console.error("    (MATEON_BASE_URL 로 서버에 닿는지도 확인하세요: " + baseUrl + ")");
        process.exit(1);
    }

    const userIdA = decodeJwtSubject(tokenA);
    const userIdB = decodeJwtSubject(tokenB);
    if (userIdA === userIdB) {
        console.error("\n(!) A 와 B 가 동일 계정입니다. .env 의 MATEON_USERB_EMAIL 을 다른 이메일로 지정하세요.");
        process.exit(1);
    }
    console.log(`(i) A userId=${userIdA} / B userId=${userIdB}`);

    const roomId = await ensureDmRoom(tokenA, userIdB);
    console.log(`(i) DM roomId = ${roomId}`);

    console.log("\n[2] 채팅 창 2개를 띄웁니다...");
    startChatWindow({ label: "A", email: userA.email, password: userA.password, roomId, color: "cyan" });
    startChatWindow({ label: "B", email: userB.email, password: userB.password, roomId, color: "green" });

    console.log("\n두 창이 열렸습니다. 아무 창에서나 메시지를 입력하면 상대 창에 실시간으로 표시됩니다.");
    console.log(`  - A 채팅 창: ${userA.email} (userId=${userIdA})`);
    console.log(`  - B 채팅 창: ${userB.email} (userId=${userIdB})`);
    console.log(`  - 방 번호: #${roomId}`);
    console.log("각 창은 /quit 또는 /exit 로 종료하세요.");
}

main().catch((err) => {
    console.error(`(!) ${err.message}`);
    process.exit(1);
});
