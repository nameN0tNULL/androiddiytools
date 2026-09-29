#!/usr/bin/env python3
import json, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT=int(os.environ.get('MOCK_LLAMA_PORT','18081'))
LOG=os.environ.get('MOCK_LLAMA_LOG','/tmp/mock-llama.jsonl')

def write_log(obj):
    with open(LOG,'a',encoding='utf-8') as f:
        f.write(json.dumps(obj,ensure_ascii=False)+'\n')

def content_from(body):
    return '\n'.join(m.get('content','') for m in body.get('messages',[]) if isinstance(m,dict))

class H(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def reply(self, code, obj):
        data=json.dumps(obj,ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path=='/health':
            return self.reply(200,{'ok':True})
        return self.reply(404,{'error':'not found'})

    def do_POST(self):
        n=int(self.headers.get('Content-Length','0'))
        raw=self.rfile.read(n)
        try:
            body=json.loads(raw or b'{}')
        except Exception:
            return self.reply(400,{'error':'bad json'})
        text=content_from(body)
        write_log({'path':self.path,'body':body})
        if self.path.endswith('/chat/completions/input_tokens'):
            recent=text.split('[Recent History]')[-1].split('[Glossary]')[0] if '[Recent History]' in text else ''
            n_hist=recent.count('你好。')
            memory=text.split('[Compact Memory]')[-1].split('[Recent History]')[0] if '[Compact Memory]' in text else ''
            memory_bonus=35 if '(无)' not in memory else 0
            return self.reply(200,{'input_tokens':80+n_hist*80+memory_bonus})
        if self.path.endswith('/chat/completions'):
            is_compact='翻译会话压缩器' in text
            answer='人物：测试角色。\n剧情：已压缩旧历史。' if is_compact else '你好。'
            return self.reply(200,{
                'choices':[{'message':{'role':'assistant','content':answer}}],
                'tokens_cached':120 if not is_compact else 0,
                'tokens_evaluated':20 if not is_compact else 60,
                'timings':{'prompt_ms':12.5,'predicted_n':8,'predicted_ms':20.0}
            })
        return self.reply(404,{'error':'not found'})

ThreadingHTTPServer(('127.0.0.1',PORT),H).serve_forever()
