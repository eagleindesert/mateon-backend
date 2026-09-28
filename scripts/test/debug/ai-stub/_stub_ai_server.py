"""PowerShell-free implementation of the Mateon AI integration stub."""
import argparse
import json
import random
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=8000)
parser.add_argument('--embedding-dimension', type=int, default=1536)
parser.add_argument('--expected-secret', default='')
args = parser.parse_args()

known_paths = {
    '/intents/extract', '/internal/teams/embedding:refresh',
    '/internal/contests/embedding:refresh', '/contests/similarity-map',
    '/recommendations/user-to-team', '/recommendations/team-to-user',
    '/recommendations/reason', '/selection-events',
    '/proposals/user-to-team', '/proposals/team-to-user',
    '/contests/extract-image', '/portfolios/summarize',
}
counters = {'portfolio': 0, 'reason': 0, 'proposal': 0}


def vector():
    return [round(random.random() * 2 - 1, 6) for _ in range(args.embedding_dimension)]


def field_label(field):
    return {'EDUCATION': '교육', 'PLANNING_IDEA': '기획/아이디어'}.get(field, field)


def event_summary(item):
    keys = ('id', 'title', 'organizer', 'category', 'field', 'detail_url')
    result = {key: item.get(key) for key in keys}
    result['field_label'] = field_label(item.get('field'))
    return result


def component_scores(matched, score):
    return {'similarity': round(score * 0.85, 4),
            'role_match': 1.0 if matched else 0.0,
            'deficit_fit': 1.0 if matched else 0.0,
            'activity_style_match': 0.5,
            'beginner_fit': 1.0,
            'activity_time_match': 0.0}


def recommendations(body, reverse):
    query = body.get('query_metadata') or {}
    candidates = body.get('candidates') or []
    rows = []
    for index, candidate in enumerate(candidates):
        meta = candidate.get('metadata') or {}
        if reverse:
            desired = meta.get('desired_roles') or []
            recruiting = query.get('recruiting_roles') or []
        else:
            desired = query.get('desired_roles') or []
            recruiting = meta.get('recruiting_roles') or []
        matched_roles = [role for role in recruiting if role in desired]
        matched = bool(matched_roles)
        if matched:
            score = round(0.90 + index * 0.001, 4)
            label = (f'{matched_roles[0]} 역할을 희망하고 있어요' if reverse
                     else f'{matched_roles[0]} 역할을 모집하고 있어요')
        elif ((query.get('beginner_friendly') and meta.get('experience_level') == 'beginner')
              if reverse else meta.get('beginner_friendly')):
            score = round(0.30 + index * 0.001, 4)
            label = ('초보자를 환영하는 팀 분위기와 잘 맞아요' if reverse
                     else '초보자도 편하게 참여할 수 있는 팀이에요')
        else:
            score = round(0.10 + index * 0.001, 4)
            label = '의미적으로 관심사가 잘 맞아요'
        rows.append({'candidate_id': candidate.get('candidate_id'), 'score': score,
                     'label': label, 'component_scores': component_scores(matched, score)})
    return {'recommendations': sorted(rows, key=lambda row: row['score'])}


def similarity_map(body):
    query = event_summary(body.get('query') or {})
    candidates = body.get('candidates') or []
    points = []
    for index, candidate in enumerate(candidates):
        percentile = round(index / (len(candidates) - 1), 4) if len(candidates) > 1 else 0.0
        radius = round(2.6 + percentile * (12.0 - 2.6), 3)
        point = event_summary(candidate)
        point.update({'similarity': round(0.9 - index * 0.05, 3),
                      'rank_percentile': percentile, 'radius': radius,
                      'x': round(-radius, 3), 'y': round(0.025 * (index + 1), 3)})
        points.append(point)
    rings = []
    if points:
        for percentile, radius in ((0.1, 3.54), (0.3, 5.42), (0.6, 8.24), (0.9, 11.06)):
            rings.append({'percentile': percentile,
                          'similarity_at_percentile': points[0]['similarity'], 'radius': radius})
    return {'query': query, 'points': points, 'max_radius': 12.0,
            'min_radius': 2.6, 'radial_jitter': 0.5,
            'reference_rings': rings, 'candidate_pool_total': len(candidates)}


def intent(body):
    messages = body.get('messages') or []
    for item in messages:
        message = str(item.get('message') or '')
        print(f"id={item.get('id')} role={item.get('role', 'user')} message={message[:80]}", flush=True)
    ids_ok = all(item.get('id') == index for index, item in enumerate(messages, 1))
    print('[OK] id 연속 증가' if ids_ok else '[!!] id 불연속', flush=True)
    dialogue = [item for item in messages if not str(item.get('message') or '').startswith(('[자기소개서]', '[포트폴리오]'))]
    extracted = {'desired_roles': ['BE'], 'skills': ['React', 'TypeScript'],
                 'interests': [] if len(dialogue) <= 1 else ['커머스'],
                 'activity_goal': '포트폴리오용 프로젝트',
                 'activity_style': None if len(dialogue) <= 1 else '주 2회 오프라인',
                 'experience_level': None if len(dialogue) <= 1 else 'beginner'}
    if len(dialogue) <= 1:
        return {'missing_fields': ['experience_level'], 'extracted': extracted,
                'embedding_text': None, 'embedding_vector': None,
                'assistant_message': '포트폴리오용 프로젝트를 찾고 있구나! 혹시 경험 수준이 어느 정도인지 알려줄 수 있어? (입문/중급/고급)'}
    return {'missing_fields': [], 'extracted': extracted,
            'embedding_text': '백엔드 / React, TypeScript / 커머스 / 포트폴리오용 프로젝트 / 주 2회 오프라인 / beginner',
            'embedding_vector': vector(),
            'assistant_message': '너의 관심사는 백엔드구나! 너의 취향을 조금 알 것 같아. 이건 내가 추천해주는 팀 후보야.'}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *values):
        print('[ai-stub]', fmt % values, flush=True)

    def send_json(self, value, status=200):
        data = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/__stub':
            self.send_json({'stub': 'mateon-ai-stub',
                            'embedding_dimension': args.embedding_dimension})
        else:
            self.send_json({'detail': 'Not Found'}, 404)

    def do_POST(self):
        if self.path not in known_paths:
            self.send_json({'detail': 'Not Found'}, 404)
            return
        secret = self.headers.get('X-Internal-Secret')
        if secret:
            print(f'X-Internal-Secret: present (len={len(secret)})', flush=True)
        else:
            print('[!!] X-Internal-Secret 헤더 없음', flush=True)
        if args.expected_secret:
            if not secret:
                self.send_json({'detail': 'Missing X-Internal-Secret'}, 401)
                return
            if secret != args.expected_secret:
                self.send_json({'detail': 'Invalid X-Internal-Secret'}, 401)
                return
        raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if self.path == '/contests/extract-image':
            head = raw[:512].decode(errors='replace')
            print(f'Content-Type: {self.headers.get("Content-Type")} bytes={len(raw)}', flush=True)
            print('[OK] img_file' if 'name="img_file"' in head else '[!!] img_file 없음', flush=True)
            self.send_json({'external_id': None, 'category': 'CONTEST', 'field': 'PLANNING_IDEA',
                            'title': '2026 제10회 <051영화제> 51초 영화 공모전',
                            'organizer': '부산시사회복지협의회', 'target_school': None,
                            'start_date': '2026-07-01', 'end_date': '2026-07-31',
                            'detail_url': None, 'image_url': None,
                            'description': "주제\n'연결'\n\n(스텁 응답)",
                            'summarized_description': "부산시사회복지협의회에서 주관하는 '51초 영화 공모전' 공고입니다.",
                            'recommended_targets': '대상 제한 없음, 역량 강화 및 포트폴리오 구축 희망자'})
            return
        if self.path == '/portfolios/summarize':
            counters['portfolio'] += 1
            head = raw[:512].decode(errors='replace')
            print('[OK] pdf_file' if 'name="pdf_file"' in head else '[!!] pdf_file 없음', flush=True)
            self.send_json({'pdf_id': '0' * 64,
                            'response': f"- [stub#{counters['portfolio']}] OO 서비스 프론트엔드 개발, React/TypeScript 로 대시보드 UI 구현\n"
                                        '- OO 해커톤 팀 프로젝트, 백엔드 API 설계 및 배포\n\n'
                                        '요약\n이 사용자는 프론트엔드를 중심으로 실무 프로젝트 경험을 쌓아왔으며, 백엔드 협업 경험도 일부 있습니다.'})
            return
        try:
            body = json.loads(raw)
        except (ValueError, TypeError):
            self.send_json({'detail': 'Invalid JSON'}, 400)
            return
        print(f'{self.path}: {json.dumps(body, ensure_ascii=False)[:1200]}', flush=True)
        if self.path == '/internal/teams/embedding:refresh':
            roles = body.get('recruiting_roles') or []
            skills = body.get('required_skills') or []
            self.send_json({'missing_fields': ['activity_intensity'],
                            'embedding_text': f"팀 소개: {body.get('intro_text')}\n모집 역할: {', '.join(roles)}\n요구 스킬: {', '.join(skills)}",
                            'embedding_vector': vector(),
                            'metadata': {'recruiting_roles': roles, 'required_skills': skills,
                                         'activity_goal': '교내 공모전 수상',
                                         'activity_style': '오프라인 모임', 'beginner_friendly': True}})
        elif self.path == '/internal/contests/embedding:refresh':
            self.send_json({'event_id': body.get('event_id'), 'embedding_vector': vector()})
        elif self.path == '/contests/similarity-map':
            self.send_json(similarity_map(body))
        elif self.path == '/recommendations/user-to-team':
            self.send_json(recommendations(body, False))
        elif self.path == '/recommendations/team-to-user':
            self.send_json(recommendations(body, True))
        elif self.path == '/selection-events':
            context = body.get('selection_context') or {}
            shown = context.get('shown_candidates') or []
            print(f"direction={body.get('direction')} selected={body.get('selected_candidate_id')} shown={len(shown)}", flush=True)
            self.send_json({'accepted': True})
        elif self.path == '/recommendations/reason':
            counters['reason'] += 1
            reason = (f"[stub#{counters['reason']}] 후보({body.get('candidate_summary')})와 "
                      f"대상({body.get('target_summary')})은 {body.get('score_context')} 기준으로 잘 맞습니다.")
            self.send_json({'reason': reason})
        elif self.path in ('/proposals/user-to-team', '/proposals/team-to-user'):
            counters['proposal'] += 1
            direction = 'USER_TO_TEAM' if self.path.endswith('user-to-team') else 'TEAM_TO_USER'
            expected_sender = body.get('user_id') if direction == 'USER_TO_TEAM' else body.get('team_id')
            expected_receiver = body.get('team_id') if direction == 'USER_TO_TEAM' else body.get('user_id')
            print('[OK] sender/receiver' if body.get('sender_id') == expected_sender and body.get('receiver_id') == expected_receiver
                  else '[!!] sender/receiver 뒤바뀜', flush=True)
            result = {key: body.get(key) for key in ('user_id', 'team_id', 'contest_id', 'sender_id',
                                                      'receiver_id', 'intent_id', 'synergy_score')}
            result.update({'direction': direction, 'portfolio_role_fit_score': None,
                           'summary': f"[stub#{counters['proposal']}] {body.get('candidate_summary')} 를 바탕으로 함께하고 싶습니다.",
                           'message': f"[stub#{counters['proposal']}] 안녕하세요. {body.get('target_summary')} 에 관심이 있어 연락드립니다."})
            self.send_json(result)
        else:
            self.send_json(intent(body))


print(f'AI 스텁: http://localhost:{args.port}/ (embedding dimension={args.embedding_dimension})', flush=True)
try:
    HTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
except KeyboardInterrupt:
    pass
