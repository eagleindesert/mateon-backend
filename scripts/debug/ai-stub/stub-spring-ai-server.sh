#!/usr/bin/env bash
# OpenAI Chat Completions 호환 라우터 스텁 (Python 표준 라이브러리 HTTP 서버).
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 - "$script_dir/../../lib" "$@" <<'PY'
import argparse
import json
import sys
sys.path.insert(0,sys.argv.pop(1))
from colors import color_print
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=8001)
parser.add_argument('--force-domain', choices=['MATCHING_INTENT','UNCLEAR','OUT_OF_SCOPE'], default='')
parser.add_argument('--failure-mode', choices=['none','broken-json','unknown-domain','empty-domain','http-500','temperature-400'], default='none')
args = parser.parse_args(sys.argv[1:])
count = 0

def completion(model, content):
    return {'id':'chatcmpl-stub-'+uuid.uuid4().hex[:12], 'object':'chat.completion',
            'created':int(time.time()), 'model':model,
            'choices':[{'index':0,'message':{'role':'assistant','content':content,'refusal':None},
                        'logprobs':None,'finish_reason':'stop'}],
            'usage':{'prompt_tokens':0,'completion_tokens':0,'total_tokens':0}}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *values):
        color_print('Cyan', '[router]', fmt % values, flush=True)

    def send_json(self, value, status=200):
        color = 'Yellow' if status == 404 or args.failure_mode != 'none' else 'Green' if status < 400 else 'Red'
        color_print(color, f'  -> {status}', flush=True)
        data=json.dumps(value,ensure_ascii=False,separators=(',',':')).encode()
        self.send_response(status)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        global count
        if self.path not in ('/v1/chat/completions','/chat/completions'):
            self.send_json({'error':{'message':'Unknown path','type':'invalid_request_error'}},404)
            return
        if self.headers.get('Authorization'):
            color_print('Yellow', '[!!] Authorization 헤더가 실려 왔습니다', flush=True)
        try:
            body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
        except (ValueError,TypeError):
            self.send_json({'error':{'message':'Invalid JSON','type':'invalid_request_error'}},400)
            return
        model=body.get('model') or 'unknown'
        messages=body.get('messages') or []
        system='\n'.join(str(m.get('content') or '') for m in messages if m.get('role')=='system')
        users=[m.get('content') or '' for m in messages if m.get('role')=='user']
        raw=str(users[-1]) if users else ''
        utterance=raw
        for marker in ('Your response should be in JSON','Here is the JSON Schema','```'):
            utterance=utterance.split(marker,1)[0]
        utterance=utterance.strip()
        color_print('Gray', f'model={model} temperature={body.get("temperature")} utterance={utterance}', flush=True)
        missing=[x for x in ('MATCHING_INTENT','UNCLEAR','OUT_OF_SCOPE') if x not in system]
        color_print('Red' if missing else 'Green', '[OK] 도메인 카탈로그' if not missing else f'[!!] 빠진 도메인: {missing}', flush=True)
        if args.failure_mode=='http-500':
            self.send_json({'error':{'message':'stub injected failure','type':'server_error'}},500)
            return
        if args.failure_mode=='temperature-400' and body.get('temperature') is not None and body['temperature']!=1:
            detail=f"Unsupported value: 'temperature' does not support {body['temperature']} with this model. Only the default (1) is supported."
            self.send_json({'error':{'message':detail,'type':'invalid_request_error',
                                     'param':'temperature','code':'unsupported_value'}},400)
            return
        count+=1
        failure_contents={'broken-json':'죄송하지만 JSON 으로 답할 수 없습니다',
                          'unknown-domain':'{"domain":"WEATHER_FORECAST","assistantMessage":"맑아요"}',
                          'empty-domain':'{"assistantMessage":"문구만 있음"}'}
        if args.failure_mode in failure_contents:
            content=failure_contents[args.failure_mode]
        else:
            out_words=('날씨','주식','로또','뉴스','맛집','요리','레시피','영화','환율','연예인','문법')
            unclear_words=('안녕','하이','헬로','ㅎㅇ','도와줘','도와주세요','뭐해','뭐하는')
            if args.force_domain: domain=args.force_domain
            elif any(word in utterance for word in out_words): domain='OUT_OF_SCOPE'
            elif any(word in utterance for word in unclear_words): domain='UNCLEAR'
            else: domain='MATCHING_INTENT'
            if domain=='MATCHING_INTENT': assistant=''
            elif domain=='UNCLEAR': assistant=f'어떤 걸 도와드릴까요? 찾고 계신 팀이나 활동을 알려주세요. [stub#{count}]'
            else: assistant=f'그 주제는 도와드리기 어려워요. 저는 함께할 팀이나 팀원을 찾는 걸 도와드릴 수 있어요. [stub#{count}]'
            content=json.dumps({'domain':domain,'assistantMessage':assistant},ensure_ascii=False,separators=(',',':'))
        self.send_json(completion(model,content))

    def do_GET(self):
        self.send_json({'error':{'message':'Unknown path','type':'invalid_request_error'}},404)

color_print('Magenta', 'Spring AI 라우터 스텁', flush=True)
color_print('Green', f'라우터 스텁: http://localhost:{args.port}/v1/chat/completions',flush=True)
try:
    ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
except KeyboardInterrupt:
    color_print('DarkGray', '라우터 스텁을 중지했습니다.', flush=True)
PY
