#!/usr/bin/env node
// chat-client.js — 한 유저용 실시간 채팅 클라이언트 (창 1개 = 유저 1명).
// launch.js 가 이 스크립트를 새 콘솔 창 2개로 각각 띄운다 (--config 로 자격증명 전달).
// 직접 실행도 가능하다 (아래 "수동 실행" 참고).
//
// PowerShell 판(../../for-api-server/parallel-chat/chat-client.ps1)과 프로토콜은 동일하다:
//   - 엔드포인트: /ws-stomp (네이티브 WebSocket)
//   - 인증: STOMP CONNECT 프레임의 native header "Authorization: Bearer <token>"
//     (브라우저/undici WebSocket API 는 핸드셰이크에 커스텀 HTTP 헤더를 못 붙이므로
//      서버도 HTTP 헤더가 아닌 STOMP 프레임 헤더로 인증한다 — StompAuthChannelInterceptor)
//   - 발행: /app/chat.send  { roomId, content }
//   - 구독: /topic/room.{roomId}
//
// Node 18+ 의 전역 fetch, Node 21+ 의 전역 WebSocket 만 사용하므로 npm install 이 필요 없다.
//
// launch.js 가 넘겨주는 방식 (권장):
//   node chat-client.js --config <launch.js 가 만든 임시 JSON 파일 경로>
//   (파일은 읽는 즉시 삭제 — 비밀번호가 디스크에 남지 않는다)
//
// 수동 실행 (이미 roomId 를 알고 있을 때):
//   node chat-client.js --label A --email test10@snu.ac.kr --password ****** --room 3 --color cyan
//   (비밀번호가 쉘 히스토리에 남으므로, 가능하면 launch.js 사용을 권장)
//
// 메시지 입력 후 Enter 로 전송, /quit 또는 /exit 로 종료.

"use strict";

const fs = require("fs");
const readline = require("readline");

// ----------------------------------------------------------------------------
// 1) 설정 로드: --config <json> 우선, 없으면 개별 CLI 인자
// ----------------------------------------------------------------------------
function parseArgs(argv) {
    const args = {};
    for (let i = 0; i < argv.length; i++) {
        if (!argv[i].startsWith("--")) continue;
        const key = argv[i].slice(2);
        const next = argv[i + 1];
        if (next !== undefined && !next.startsWith("--")) {
            args[key] = next;
            i++;
        } else {
            args[key] = true;
        }
    }
    return args;
}
const args = parseArgs(process.argv.slice(2));

let config;
if (args.config) {
    const raw = fs.readFileSync(args.config, "utf8");
    try { fs.unlinkSync(args.config); } catch { /* 이미 지워졌으면 무시 */ }
    config = JSON.parse(raw);
} else {
    config = {
        label: args.label,
        email: args.email,
        password: args.password,
        roomId: args.room,
        baseUrl: args["base-url"],
        color: args.color,
    };
}

const baseUrl = config.baseUrl || "http://localhost:8080";
const email = config.email;
const password = config.password;
const label = config.label || email || "me";
const roomId = config.roomId;

const COLOR_CODES = {
    red: 31, green: 32, yellow: 33, blue: 34, magenta: 35, cyan: 36,
};
const colorCode = COLOR_CODES[config.color] || COLOR_CODES.cyan;
const colorize = (text) => `\u001b[${colorCode}m${text}\u001b[0m`;

if (!email || !password || !roomId) {
    console.error("--config <json> 또는 (--label/--email/--password/--room) 조합이 필요합니다.");
    console.error("보통은 직접 실행하지 않고 launch.js 로 띄웁니다: node launch.js");
    process.exit(1);
}

try { process.title = `Chat [${label}] ${email}`; } catch { /* 일부 터미널은 미지원 */ }

// ----------------------------------------------------------------------------
// 2) REST: 로그인 (토큰은 메모리에만 보관 — 파일 저장 안 함)
// ----------------------------------------------------------------------------
async function login() {
    const res = await fetch(`${baseUrl}/api/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
    });
    const json = await res.json().catch(() => null);
    if (!res.ok || !json?.data?.accessToken) {
        throw new Error(`로그인 실패 (${res.status}): ${JSON.stringify(json)}`);
    }
    return json.data.accessToken;
}

function decodeJwtSubject(token) {
    const payload = token.split(".")[1];
    const normalized = payload.replace(/-/g, "+").replace(/_/g, "/");
    const json = Buffer.from(normalized, "base64").toString("utf8");
    return JSON.parse(json).sub;
}

async function fetchRecentHistory(token) {
    const res = await fetch(`${baseUrl}/api/chat/rooms/${roomId}/messages?size=8`, {
        headers: { Authorization: `Bearer ${token}` },
    });
    const json = await res.json().catch(() => null);
    return json?.data || [];
}

// ----------------------------------------------------------------------------
// 3) STOMP 프레임 인코딩/디코딩 (COMMAND\n header:value\n\n body\0)
// ----------------------------------------------------------------------------
const NUL = "\u0000";

function buildFrame(command, headers = {}, body = "") {
    const headerLines = Object.entries(headers).map(([k, v]) => `${k}:${v}\n`).join("");
    return `${command}\n${headerLines}\n${body}${NUL}`;
}

function parseFrame(raw) {
    const clean = raw.endsWith(NUL) ? raw.slice(0, -1) : raw;
    const sep = clean.indexOf("\n\n");
    const headerPart = sep >= 0 ? clean.slice(0, sep) : clean;
    const body = sep >= 0 ? clean.slice(sep + 2) : "";
    const [command, ...headerLines] = headerPart.split("\n");
    const headers = {};
    for (const line of headerLines) {
        const idx = line.indexOf(":");
        if (idx > 0) headers[line.slice(0, idx)] = line.slice(idx + 1);
    }
    return { command, headers, body };
}

// ----------------------------------------------------------------------------
// 4) 메인 흐름
// ----------------------------------------------------------------------------
async function main() {
    console.log("=".repeat(64));
    console.log(` 실시간 채팅 클라이언트  [${label}]  ${email}`);
    console.log("=".repeat(64));

    const token = await login();
    const userId = decodeJwtSubject(token);
    console.log(`(i) 로그인 성공. userId=${userId}`);

    const history = await fetchRecentHistory(token).catch(() => []);
    if (history.length > 0) {
        console.log("\n--- 최근 대화 ---");
        for (const h of history) {
            const who = String(h.senderId) === String(userId) ? "나" : h.senderName;
            console.log(`  ${who}: ${h.content}`);
        }
        console.log("-----------------\n");
    }

    const wsUrl = baseUrl.replace(/^http/, "ws") + "/ws-stomp";
    const ws = new WebSocket(wsUrl);

    await new Promise((resolve, reject) => {
        ws.addEventListener("open", () => resolve(), { once: true });
        ws.addEventListener("error", () => reject(new Error(`WebSocket 연결 실패: ${wsUrl}`)), { once: true });
    });

    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("STOMP CONNECT 타임아웃")), 5000);
        function onConnectResponse(event) {
            const frame = parseFrame(event.data.toString());
            if (frame.command === "CONNECTED") {
                clearTimeout(timer);
                ws.removeEventListener("message", onConnectResponse);
                resolve();
            } else if (frame.command === "ERROR") {
                clearTimeout(timer);
                ws.removeEventListener("message", onConnectResponse);
                reject(new Error(`STOMP CONNECT 거부: ${frame.body}`));
            }
        }
        ws.addEventListener("message", onConnectResponse);
        ws.send(buildFrame("CONNECT", {
            "accept-version": "1.2",
            host: "localhost",
            "heart-beat": "0,0",
            Authorization: `Bearer ${token}`,
        }));
    });

    ws.send(buildFrame("SUBSCRIBE", { id: `sub-${label}`, destination: `/topic/room.${roomId}` }));

    console.log("");
    console.log(colorize(`연결됨 — room #${roomId} 구독 완료. 메시지를 입력하고 Enter 로 전송하세요.`));
    console.log("(종료: /quit 또는 /exit)  노랑=상대 수신, 회색=내 메시지 서버 반영 확인");
    console.log("");

    ws.addEventListener("message", (event) => {
        const frame = parseFrame(event.data.toString());
        if (frame.command !== "MESSAGE") return;
        let payload;
        try { payload = JSON.parse(frame.body); } catch { payload = { content: frame.body }; }
        const mine = String(payload.senderId) === String(userId);
        if (mine) {
            console.log(`\u001b[90m나 ✓  ${payload.content ?? ""}\u001b[0m`);
        } else {
            console.log(`\u001b[33m\n${payload.senderName || `user${payload.senderId}`} ▶  ${payload.content ?? ""}\u001b[0m`);
        }
    });

    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: `${label}(나)> ` });
    rl.prompt();
    rl.on("line", (line) => {
        const text = line.trim();
        if (text === "/quit" || text === "/exit") {
            rl.close();
            return;
        }
        if (text) {
            ws.send(buildFrame(
                "SEND",
                { destination: "/app/chat.send", "content-type": "application/json" },
                JSON.stringify({ roomId: Number(roomId), content: text }),
            ));
        }
        rl.prompt();
    });
    rl.on("close", () => {
        console.log("\n연결 종료 중...");
        try { ws.send(buildFrame("DISCONNECT", {})); } catch { /* 이미 끊긴 경우 무시 */ }
        ws.close();
        console.log(`종료되었습니다. [${label}]`);
        process.exit(0);
    });
}

main().catch((err) => {
    console.error(`(!) ${err.message}`);
    process.exitCode = 1;
});
