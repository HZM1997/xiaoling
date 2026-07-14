/*
 * 小灵 · Live2D 形象适配层(可选)
 * ─────────────────────────────────────────────────────────────
 * 作用:把 player.html 的情绪状态机(setMascot / mascotTalk)映射到一个真实的
 *       Live2D Cubism 模型上,替换默认的 SVG 小蜜蜂。缺库/缺模型时自动放弃、保留 SVG。
 *
 * 用法:player.html?live2d=1   (player.html 会自动加载本文件)
 * 依赖(运行时从 CDN 拉取,需联网):
 *   - Live2D Cubism Core        (官方 live2dcubismcore.min.js)
 *   - PIXI.js                    (pixi.js)
 *   - pixi-live2d-display        (驱动库)
 * 模型:把你自己的 Cubism 模型(.model3.json)地址填到 CONFIG.MODEL_URL。
 *       测试可用官方免费样例(见 LIVE2D.md,注意 Live2D 授权条款)。
 *
 * 说明:表情名(EXPRESSIONS)要对应你模型里真实的 expression 名;不同模型不一样,
 *       按 LIVE2D.md 的方法查出来改这里即可。改不动也不影响——会退回 SVG 小蜜蜂。
 */
(function () {
  'use strict';

  var CONFIG = {
    // 默认接入官方免费样例「Haru」(pixi-live2d-display 测试资源,联网即用)。
    // ⚠ 授权:Live2D 官方免费样例受 Live2D「免费材料使用许可协议」约束 —— 仅供测试/非商用。
    //   商用请替换为你自研或已购授权的模型(把下面 URL 换掉即可)。
    MODEL_URL: 'https://cdn.jsdelivr.net/gh/guansss/pixi-live2d-display/test/assets/haru/haru_greeter_t03.model3.json',
    // 依赖 CDN(可换成本地文件以离线使用)
    CDN: {
      cubismCore: 'https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js',
      pixi: 'https://cdn.jsdelivr.net/npm/pixi.js@6.5.10/dist/browser/pixi.min.js',
      display: 'https://cdn.jsdelivr.net/npm/pixi-live2d-display@0.4.0/dist/index.min.js',
    },
    // 状态 → 模型表情名(Haru 样例的表情为 f01~f08;按你的模型改)
    EXPRESSIONS: {
      idle: 'f01', happy: 'f02', listen: 'f01', think: 'f04',
      alarm: 'f07', help: 'f03', caring: 'f02',
    },
    // 状态 → 动作组名(可选,按你的模型改;没有就留空)
    MOTIONS: {
      happy: 'tap_body', alarm: 'flick_head', help: '', idle: 'idle',
    },
    MOUTH_PARAM: 'ParamMouthOpenY',   // 口型参数 id(大多数模型是这个)
  };

  var model = null, app = null, talking = false, pending = 'idle';

  // ── 对外接口:player.html 通过 window.XLAvatar 驱动 ──
  window.XLAvatar = {
    setState: function (s) {
      pending = s;
      if (!model) return;
      var exp = CONFIG.EXPRESSIONS[s];
      if (exp && model.expression) { try { model.expression(exp); } catch (e) {} }
      var mo = CONFIG.MOTIONS[s];
      if (mo && model.motion) { try { model.motion(mo); } catch (e) {} }
    },
    setTalking: function (on) { talking = on; },
    isReady: function () { return !!model; },
  };

  function loadScript(src) {
    return new Promise(function (res, rej) {
      var s = document.createElement('script');
      s.src = src; s.onload = res; s.onerror = function () { rej(new Error('加载失败: ' + src)); };
      document.head.appendChild(s);
    });
  }

  async function boot() {
    if (!CONFIG.MODEL_URL) {
      console.info('[小灵·Live2D] 未配置 MODEL_URL,保留 SVG 形象。填 live2d.js 里的 CONFIG.MODEL_URL 后重试。');
      return;
    }
    try {
      await loadScript(CONFIG.CDN.cubismCore);
      await loadScript(CONFIG.CDN.pixi);
      await loadScript(CONFIG.CDN.display);
    } catch (e) {
      console.warn('[小灵·Live2D] 依赖加载失败(可能离线),保留 SVG:', e.message);
      return;
    }
    var PIXI = window.PIXI;
    if (!PIXI || !PIXI.live2d) { console.warn('[小灵·Live2D] 运行时缺失,保留 SVG'); return; }

    var stage = document.getElementById('stage');
    var svg = stage && stage.querySelector('.char');
    var canvas = document.createElement('canvas');
    canvas.style.cssText = 'position:absolute;inset:0;z-index:2;width:100%;height:100%';
    stage.appendChild(canvas);

    app = new PIXI.Application({ view: canvas, backgroundAlpha: 0, resizeTo: stage, antialias: true });
    try {
      model = await PIXI.live2d.Live2DModel.from(CONFIG.MODEL_URL);
    } catch (e) {
      console.warn('[小灵·Live2D] 模型加载失败,保留 SVG:', e); canvas.remove(); return;
    }
    if (svg) svg.style.display = 'none';       // 成功接管后隐藏 SVG 小蜜蜂
    app.stage.addChild(model);

    // 居中缩放
    var fit = function () {
      var s = Math.min(stage.clientWidth / model.width, stage.clientHeight / model.height) * 0.92;
      model.scale.set(s);
      model.x = (stage.clientWidth - model.width) / 2;
      model.y = (stage.clientHeight - model.height) / 2;
    };
    fit(); window.addEventListener('resize', fit);

    // 口型:说话时驱动 MouthOpenY(在模型每帧更新后覆盖)
    var t = 0;
    app.ticker.add(function () {
      if (!model.internalModel) return;
      var core = model.internalModel.coreModel;
      var v = talking ? (Math.sin((t += 0.35)) * 0.5 + 0.5) : 0;
      try { core.setParameterValueById(CONFIG.MOUTH_PARAM, v); } catch (e) {}
    });

    window.XLAvatar.setState(pending);   // 应用当前状态
    console.info('[小灵·Live2D] 模型已接管形象 ✓');
  }

  if (document.readyState !== 'loading') boot();
  else document.addEventListener('DOMContentLoaded', boot);
})();
