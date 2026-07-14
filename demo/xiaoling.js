#!/usr/bin/env node
/*
 * 小灵 · AI手机精灵 —— 零依赖 Node 版大脑(可直接运行,和 Python 版逻辑一致)
 * 用法:
 *   node xiaoling.js            一键跑演示剧本(防诈/呼救/打电话/导航/陪伴)
 *   node xiaoling.js --serve    起 HTTP 服务(POST /dialogue),供安卓客户端联调
 *   node xiaoling.js --repl     交互模式,打字模拟"对精灵说的话"
 */
'use strict';

// ==================== 防诈骗风控引擎 v2(分类研判,逻辑同 server/fraud.py) ====================
const FRAUD_CATS = {
  impersonate_gov:     { label: '冒充公检法/政府', weight: 0.55, words: ['公检法','涉嫌洗钱','涉嫌犯罪','通缉令','逮捕证','安全账户','配合调查','银保监','反洗钱中心'] },
  impersonate_service: { label: '冒充客服/退款理赔', weight: 0.4, words: ['快递丢了','理赔','退款','会员到期自动扣费','关闭百万保障','误开通了业务','客服工号'] },
  loan_credit:         { label: '贷款/征信/校园贷', weight: 0.4, words: ['注销校园贷','征信有问题','征信洗白','解除分期','影响征信','先交保证金','刷流水'] },
  winning_reward:      { label: '中奖/返利/刷单', weight: 0.4, words: ['中奖','免费领','返利','刷单','垫付','做任务返现','高佣金兼职','先垫钱'] },
  medical_social:      { label: '冒充医保/社保', weight: 0.45, words: ['医保卡异常','社保卡冻结','账户被停用','补贴发放'] },
  romance_pension:     { label: '杀猪盘/养老理财', weight: 0.45, words: ['投资理财稳赚','内部消息','高回报','养老项目','以房养老','专家一对一'] },
};
const REDLINE = { label: '红线操作', words: ['屏幕共享','远程控制','下载会议软件','念一下收到的验证码','念一下短信验证码','不要挂电话','不要告诉家人','转到安全账户','把钱转到','输入银行卡密码','扫这个码'] };
const TH = { high: 0.85, medium: 0.55 };

function suspiciousNumber(caller = '') {
  const c = caller.replace(/[-\s]/g, '');
  if (!c) return false;
  if (['00','+','95','96','1010','170','171'].some((p) => c.startsWith(p) && !c.startsWith('+86'))) return true;
  const len = c.replace(/^\+/, '').length;
  return len < 7 || len > 12;
}

function analyzeFraud(caller, text = '', scene = 'incoming_call') {
  // 红线词短路
  const redHits = REDLINE.words.filter((w) => text.includes(w));
  if (redHits.length) return finalize(0.96, REDLINE.label, redHits, `对方要求「${redHits[0]}」,这是诈骗分子最典型的手法`, caller, text, scene);
  const base = suspiciousNumber(caller) ? 0.15 : 0.0;
  let best = { cat: '', score: 0 }; const allHits = [];
  for (const c of Object.values(FRAUD_CATS)) {
    const hits = c.words.filter((w) => text.includes(w));
    if (!hits.length) continue;
    allHits.push(...hits);
    const score = c.weight + 0.12 * (hits.length - 1);
    if (score > best.score) best = { cat: c.label, score };
  }
  if (!allHits.length) return finalize(base, '', [], '', caller, text, scene);
  const risk = Math.min(base + best.score, 0.99);
  return finalize(risk, best.cat, allHits, '对方提到「' + allHits.slice(0, 3).join('、') + '」,是典型诈骗话术', caller, text, scene);
}

function finalize(risk, category, hits, reason, caller, text, scene) {
  const level = risk >= TH.high ? 'high' : risk >= TH.medium ? 'medium' : 'safe';
  const report = level === 'safe' ? null
    : { scene, caller, category, risk: +risk.toFixed(2), hits, snippet: text.slice(0, 60) };
  return { risk: +risk.toFixed(2), level, category, reason, hits, suggest_hangup: level === 'high', report };
}

// ==================== 技能注册表 ====================
const SKILLS = []; // {name, priority, fn}
const skill = (name, priority, fn) => SKILLS.push({ name, priority, fn });
const clean = (s) => s.replace(/[的吧呢啊,。!\s]+$/g, '').trim();

skill('紧急呼救', 1, (u) => {
  if (!/(救命|摔倒|喘不上气|胸口疼|心脏|不行了|120|急救|晕)/.test(u.text)) return null;
  return {
    speech: '别怕,我马上帮您呼叫120,同时通知您的家人,并把位置发过去,您坚持住。',
    action: { type: 'SOS', call: '120', notify_family: true, send_location: true },
  };
});

skill('防诈骗预警', 2, (u) => {
  const ctx = u.context || {};
  if (!['incoming_call', 'sms', 'incoming_sms'].includes(ctx.scene)) return null;
  const r = analyzeFraud(ctx.caller || '', u.text, ctx.scene);
  if (r.level === 'safe') return null;
  const level = r.level === 'high' ? '极高' : '较高';
  return {
    speech: `注意!这通电话诈骗风险${level}(疑似${r.category}):${r.reason}。千万不要转账、` +
            `不要提供验证码、不要按对方说的操作。要不要我帮您挂断,并打给您的子女核实?`,
    action: { type: 'FRAUD_WARN', level: r.level, category: r.category, hangup_suggest: r.suggest_hangup, report: r.report },
    risk: r.risk,
  };
});

skill('打电话', 10, (u) => {
  const m = u.text.match(/(?:打(?:个)?电话给?|呼叫|拨打?给?)\s*(.+)/);
  if (!m) return null;
  const target = clean(m[1]) || '对方';
  return { speech: `好的,正在帮您给${target}打电话。`, action: { type: 'CALL', target } };
});

skill('导航', 20, (u) => {
  const m = u.text.match(/(?:导航到?|去|怎么去|怎么走到?)\s*(.+)/);
  if (!m) return null;
  const dest = clean(m[1].replace(/(怎么走|怎么去)$/, ''));
  if (!dest) return null;
  return {
    speech: `正在为您导航到${dest},请跟着语音走。`,
    action: { type: 'OPEN_URI', uri: `androidamap://poi?sourceApplication=xiaoling&keywords=${encodeURIComponent(dest)}&dev=0` },
  };
});

skill('听戏听歌', 30, (u) => {
  if (!/(歌|戏|剧|曲|评书|相声|音乐|唱)/.test(u.text)) return null;
  const m = u.text.match(/(?:听|放|播放|来一?段?|唱)\s*(.+)/);
  if (!m) return null;
  return { speech: `好嘞,这就给您放${clean(m[1])}。`, action: { type: 'PLAY', keyword: clean(m[1]) } };
});

skill('健康用药提醒', 40, (u) => {
  if (!/提醒我?.*(吃药|量血压|喝水|睡觉|起床)/.test(u.text)) return null;
  const m = u.text.match(/(每天|明天|今天)?\s*([0-9一二三四五六七八九十]+点(?:半|[0-9]+分?)?)/);
  const when = m ? m[0].trim() : '到点';
  return { speech: `好的,我会${when}提醒您,放心。`, action: { type: 'REMIND', raw: u.text } };
});

SKILLS.sort((a, b) => a.priority - b.priority);

// ==================== 主链路:规则优先,兜底闲聊 ====================
function offlineFallback() {
  return {
    speech: '我在听着呢。您可以跟我说『打电话给女儿』『导航到医院』,或者陪我聊聊天。有需要随时喊我。',
    skill: 'offline_fallback',
  };
}

function handle(text, context = null, user_id = 'guest') {
  const u = { text, context, user_id };
  for (const s of SKILLS) {
    const r = s.fn(u);
    if (r) return { skill: s.name, risk: 0, action: null, ...r };
  }
  // 生产环境这里换成大模型 Function-Calling(通义/豆包/文心/Claude)
  return { skill: '', risk: 0, action: null, ...offlineFallback() };
}

module.exports = { handle, analyzeFraud, SKILLS };

// ==================== 运行入口 ====================
function show(r) {
  const tag = r.skill ? `[${r.skill}]` : '';
  console.log(`  🔊 小灵${tag}: ${r.speech}`);
  if (r.action) console.log(`  ⚙️  动作: ${JSON.stringify(r.action)}`);
  if (r.risk) console.log(`  ⚠️  风险分: ${r.risk.toFixed(2)} ${'█'.repeat(Math.round(r.risk * 10))}`);
  console.log('');
}

const SCRIPT = [
  ['『喂?』接到陌生来电,对方开口', '您好我是公检法的,您涉嫌洗钱,需要把钱转到安全账户配合调查', { scene: 'incoming_call', caller: '+81 90xxxx' }],
  ['『客服说要退款』', '亲您的快递丢了要理赔,请打开屏幕共享,念一下收到的验证码', { scene: 'incoming_call', caller: '0085212345' }],
  ['『陌生号码,征信话术』', '您好这边是银行的,您的征信有问题需要解除分期,不然影响征信', { scene: 'incoming_call', caller: '17012345678' }],
  ['老人突然说', '哎哟我胸口疼,喘不上气,快不行了', null],
  ['老人对精灵说', '打电话给女儿', null],
  ['老人对精灵说', '导航到人民医院', null],
  ['老人对精灵说', '提醒我每天早上八点吃药', null],
  ['老人对精灵说', '放一段京剧', null],
  ['老人闲聊', '今天天气真好,有点想老伴了', null],
];

function runScenario() {
  console.log('='.repeat(60));
  console.log('  小灵 · 演示剧本(防诈 / 呼救 / 打电话 / 导航 / 陪伴)');
  console.log('='.repeat(60) + '\n');
  for (const [label, text, ctx] of SCRIPT) {
    console.log(`👵 ${label}`);
    console.log(`  🗣️  用户: ${text}`);
    show(handle(text, ctx));
  }
}

function runServe() {
  const http = require('http');
  const port = process.env.PORT || 8000;
  http.createServer((req, res) => {
    if (req.method === 'POST' && req.url === '/dialogue') {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        let out;
        try {
          const { text, context, user_id } = JSON.parse(body || '{}');
          out = handle(text || '', context || null, user_id || 'guest');
        } catch (e) { out = { speech: '请求解析失败', error: String(e) }; }
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify(out));
      });
    } else if (req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, skills: SKILLS.map((s) => s.name) }));
    } else {
      res.writeHead(404); res.end('not found');
    }
  }).listen(port, () => console.log(`小灵大脑已启动: http://localhost:${port}  (POST /dialogue)`));
}

function runRepl() {
  const rl = require('readline').createInterface({ input: process.stdin, output: process.stdout });
  console.log('小灵已就绪。打字模拟对精灵说的话,q 退出。');
  console.log('(模拟诈骗来电:用 call: 开头,如 call:您涉嫌洗钱请转账到安全账户)\n');
  const ask = () => rl.question('🗣️  你: ', (t) => {
    t = t.trim();
    if (['q', 'quit', 'exit'].includes(t)) return rl.close();
    if (t.startsWith('call:')) show(handle(t.slice(5), { scene: 'incoming_call', caller: '0085200000' }));
    else if (t) show(handle(t));
    ask();
  });
  ask();
}

if (require.main === module) {
  if (process.argv.includes('--serve')) runServe();
  else if (process.argv.includes('--repl')) runRepl();
  else runScenario();
}
