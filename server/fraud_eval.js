#!/usr/bin/env node
/*
 * 防诈引擎 v3 · Node 评测器(逻辑镜像 server/fraud.py,读同一份 rules + corpus)。
 * Python 未装时也能在本机跑出真实 precision/recall。
 * 用法: node server/fraud_eval.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const RULES = JSON.parse(fs.readFileSync(path.join(__dirname, 'fraud_rules.json'), 'utf8'));
const CORPUS = JSON.parse(fs.readFileSync(path.join(__dirname, 'fraud_corpus.json'), 'utf8'));
let NUMDB = { blacklist: [], whitelist: [], gov_service: [], risky_segments: [] };
try { NUMDB = JSON.parse(fs.readFileSync(path.join(__dirname, 'number_reputation.json'), 'utf8')); } catch (e) {}

function normalize(text) {
  let t = text || '';
  const nm = (RULES.normalize && RULES.normalize.map) || {};
  for (const [v, s] of Object.entries(nm)) if (t.includes(v)) t = t.split(v).join(s);
  let compact = t.replace(/[ 　]/g, '');
  for (const [v, s] of Object.entries(nm)) {
    const cv = v.replace(/ /g, '');
    if (compact.includes(cv)) compact = compact.split(cv).join(s);
  }
  return compact;
}
function numAssess(caller) {
  // 镜像 number_reputation.assess:返回 [基线分, 标签]
  const c = (caller || '').replace(/[^\d+]/g, '');
  if (!c) return [0.0, 'unknown'];
  if ((NUMDB.whitelist || []).includes(c)) return [0.0, 'white'];
  for (const seg of (NUMDB.gov_service || [])) if (c.startsWith(seg)) return [0.0, 'white'];
  if ((NUMDB.blacklist || []).includes(c)) return [0.6, 'black'];
  for (const seg of (NUMDB.risky_segments || []))
    if (c.startsWith(seg.prefix) && !c.startsWith('+86')) return [0.15, 'risky'];
  const d = c.replace(/^\+/, '');
  if (!(d.length >= 7 && d.length <= 12)) return [0.15, 'risky'];
  return [0.0, 'unknown'];
}
function amplifierHits(t) {
  const out = [];
  for (const [name, sig] of Object.entries((RULES.amplifiers && RULES.amplifiers.signals) || {}))
    if (sig.words.some(w => t.includes(w))) out.push(name);
  return out;
}
function scriptPattern(t) {
  const patterns = [
    [/(?:陌生人|网友|老师|导师|客服).{0,16}(?:投资|理财|炒股|虚拟币|带单|赚钱)/i, 0.48, '陌生关系诱导投资'],
    [/(?:投入|充值|投资).{0,16}(?:提现不了|解冻|保证金|认证金|税费)/i, 0.55, '先入金后以提现为由继续收费'],
    [/(?:儿子|女儿|孙子|孙女|领导).{0,14}(?:换号|借钱|转账|汇款|出事)/i, 0.48, '冒充熟人并提出资金要求'],
    [/(?:客服|平台).{0,16}(?:退款|理赔|自动扣费).{0,20}(?:验证码|下载|共享|转账|银行卡)/i, 0.52, '客服理由衔接敏感操作'],
    [/(?:警察|公安|检察院|法院|公检法).{0,20}(?:涉案|洗钱|通缉).{0,24}(?:转账|安全账户|保密)/i, 0.62, '权威恐吓后要求资金或保密操作'],
  ];
  for (const [pattern, score, evidence] of patterns)
    if (pattern.test(t)) return {score, evidence};
  return null;
}
function suppressorDelta(t) {
  let total = 0;
  for (const sig of Object.values((RULES.suppressors && RULES.suppressors.signals) || {}))
    if (sig.words.some(w => t.includes(w))) total += sig.sub;
  return total;
}
function levelOf(risk) {
  const th = RULES.thresholds;
  return risk >= th.high ? 'high' : risk >= th.medium ? 'medium' : 'safe';
}
function analyze(text, caller) {
  const t = normalize(text);
  const cats = RULES.categories;
  const red = cats.redline;
  const redHits = red.words.filter(w => t.includes(w));
  if (redHits.length) return { risk: 0.96, level: 'high', hits: redHits, amps: amplifierHits(t) };
  const [numBase, numTag] = numAssess(caller);
  let base = numBase;
  let bestScore = 0, allHits = [], matchedCats = 0;
  for (const [key, c] of Object.entries(cats)) {
    if (key === 'redline') continue;
    const hits = c.words.filter(w => t.includes(w));
    if (!hits.length) continue;
    allHits.push(...hits);
    matchedCats += 1;
    const score = c.weight + 0.1 * (hits.length - 1);
    if (score > bestScore) bestScore = score;
  }
  const script = scriptPattern(t);
  if (script) {
    matchedCats += 1;
    allHits.push(script.evidence);
    bestScore = Math.max(bestScore, script.score);
  }
  let risk = base + bestScore;
  if (matchedCats >= 2) risk += Math.min(0.24, 0.08 * (matchedCats - 1));
  const amps = amplifierHits(t);
  for (const k of amps) risk += RULES.amplifiers.signals[k].add;
  risk -= suppressorDelta(t);
  if (numTag === 'black') risk += 0.25;
  else if (numTag === 'white' && matchedCats < 2 && amps.length === 0)
    risk = Math.min(risk, RULES.thresholds.medium - 0.01);   // 伪造官方号+多类诈骗话术不压制
  risk = Math.max(0, Math.min(risk, 0.99));
  return { risk: +risk.toFixed(2), level: levelOf(risk), hits: allHits, amps };
}
function convLevel(turns, caller) {
  const decay = (RULES.conversation && RULES.conversation.decay_per_turn) || 0.85;
  let cum = 0;
  for (const t of turns) { const one = analyze(t, caller); cum = Math.max(one.risk, cum * decay + one.risk * 0.5); cum = Math.min(cum, 0.99); }
  return levelOf(cum);
}

// —— 评测 ——
let tp = 0, fp = 0, tn = 0, fn = 0; const miss = [];
function tally(wantDanger, gotDanger, tag) {
  if (wantDanger && gotDanger) tp++;
  else if (!wantDanger && gotDanger) { fp++; miss.push('  误报 FP :: ' + tag); }
  else if (!wantDanger && !gotDanger) tn++;
  else { fn++; miss.push('  漏报 FN :: ' + tag); }
}
for (const c of CORPUS.cases) {
  const want = c.label === 'high' || c.label === 'medium';
  const got = analyze(c.text, c.caller || '').level;
  tally(want, got === 'high' || got === 'medium', `want=${c.label} got=${got} :: ${c.text.slice(0, 26)}`);
}
for (const cv of CORPUS.conversations || []) {
  const want = cv.label === 'high' || cv.label === 'medium';
  const got = convLevel(cv.turns, cv.caller || '');
  tally(want, got === 'high' || got === 'medium', `(conv)want=${cv.label} got=${got}`);
}
const precision = tp + fp ? tp / (tp + fp) : 1;
const recall = tp + fn ? tp / (tp + fn) : 1;
const f1 = precision + recall ? (2 * precision * recall) / (precision + recall) : 0;
console.log('='.repeat(50));
console.log('防诈引擎 v3 · 准确率(Node 评测,镜像 fraud.py)');
console.log(`  精确率 precision = ${precision.toFixed(3)}  (拦的里面多少真是诈骗)`);
console.log(`  召回率 recall    = ${recall.toFixed(3)}  (真诈骗里拦到多少)`);
console.log(`  F1              = ${f1.toFixed(3)}`);
console.log(`  TP=${tp} FP=${fp} TN=${tn} FN=${fn}  样本=${tp + fp + tn + fn}`);
if (miss.length) { console.log('  未命中:'); console.log(miss.join('\n')); }
console.log('='.repeat(50));
process.exit(miss.length ? 1 : 0);
