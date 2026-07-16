'use strict';
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..');
const TITLE = '小灵智能语音助手软件 V1.0';
const LPP = 50, HALF = 30;
function collect(){
  const roots=['android-compose/app/src/main/java/com/xiaoling','server'];
  const exts=['.kt','.py']; const skip=/(\/tests?\/|_eval\.js$|\/build\/)/;
  const files=[];
  function walk(d){for(const e of fs.readdirSync(d)){const p=path.join(d,e);const s=fs.statSync(p);
    if(s.isDirectory())walk(p); else if(exts.includes(path.extname(p))&&!skip.test(p.replace(/\\/g,'/')))files.push(p);}}
  for(const r of roots){const a=path.join(ROOT,r); if(fs.existsSync(a))walk(a);}
  return files.sort();
}
function count(){
  const files=collect(); let total=0; const by={};
  for(const f of files){const n=fs.readFileSync(f,'utf8').split('\n').length; total+=n;
    const e=path.extname(f); by[e]=(by[e]||0)+n;}
  console.log('文件数:',files.length);
  for(const [e,n] of Object.entries(by))console.log('  '+e+':',n,'行');
  console.log('源程序量(总行数):',total,'行  <= 软著“源程序量”栏填这个');
  return total;
}
function esc(s){return s.replace(/\\/g,'\\\\').replace(/\(/g,'\\(').replace(/\)/g,'\\)');}
function pdf(){
  const files=collect(); const lines=[];
  for(const f of files){const rel=path.relative(ROOT,f).replace(/\\/g,'/');
    lines.push('// ===== '+rel+' ====='); 
    for(const ln of fs.readFileSync(f,'utf8').replace(/\r\n/g,'\n').split('\n'))lines.push(ln.length?ln:' ');}
  const totalPages=Math.ceil(lines.length/LPP);
  let idx; if(totalPages<=2*HALF)idx=[...Array(totalPages).keys()];
  else idx=[...Array(HALF).keys(),...Array.from({length:HALF},(_,k)=>totalPages-HALF+k)];
  const pages=idx.map(pi=>{const sl=lines.slice(pi*LPP,pi*LPP+LPP); while(sl.length<LPP)sl.push(' ');
    return [TITLE+'   第 '+(pi+1)+' 页','-'.repeat(90),...sl];});
  const chunks=[]; let off=0; const enc=s=>Buffer.from(s,'latin1');
  const add=s=>{const b=enc(s);chunks.push(b);const a=off;off+=b.length;return a;};
  add('%PDF-1.4\n');
  const n=pages.length; const pid=[],cid=[]; let on=3;
  for(let i=0;i<n;i++){pid.push(on++);cid.push(on++);} const fid=on++;
  const offs={}; const obj=(id,s)=>{offs[id]=off;add(id+' 0 obj\n'+s+'\nendobj\n');};
  obj(1,'<< /Type /Catalog /Pages 2 0 R >>');
  obj(2,'<< /Type /Pages /Kids ['+pid.map(x=>x+' 0 R').join(' ')+'] /Count '+n+' >>');
  for(let i=0;i<n;i++){let st='BT /F1 9 Tf 40 800 Td 11 TL\n';
    for(const ln of pages[i]){const safe=esc(ln.replace(/[^\x20-\x7e]/g,'?').slice(0,110)); st+='('+safe+') Tj T*\n';}
    st+='ET';
    obj(pid[i],'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 '+fid+' 0 R >> >> /Contents '+cid[i]+' 0 R >>');
    obj(cid[i],'<< /Length '+st.length+' >>\nstream\n'+st+'\nendstream');}
  obj(fid,'<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>');
  const xs=off; add('xref\n0 '+(fid+1)+'\n'); add('0000000000 65535 f \n');
  for(let id=1;id<=fid;id++)add(String(offs[id]||0).padStart(10,'0')+' 00000 n \n');
  add('trailer\n<< /Size '+(fid+1)+' /Root 1 0 R >>\nstartxref\n'+xs+'\n%%EOF');
  const dir=path.join(ROOT,'copyright_out'); if(!fs.existsSync(dir))fs.mkdirSync(dir);
  const out=path.join(dir,'source_code.pdf'); fs.writeFileSync(out,Buffer.concat(chunks));
  console.log('OK 已生成:',path.relative(ROOT,out),' 共',n,'页');
}
const cmd=process.argv[2]||'count';
if(cmd==='count')count(); else if(cmd==='pdf'){count();console.log('---');pdf();}
else console.log('用法: node tools/copyright_pack.js [count|pdf]');
