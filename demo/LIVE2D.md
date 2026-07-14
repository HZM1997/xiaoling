# 用 Live2D 精致形象替换 SVG 小蜜蜂

`player.html` 的形象由一套**情绪状态机**驱动(`setMascot(state)` / `mascotTalk(on)`)。
`live2d.js` 是一个**适配层**:把这些状态映射到真实的 Live2D Cubism 模型上,替换默认 SVG。
缺库/缺模型/离线时会**自动放弃并保留 SVG 小蜜蜂**,不会报错。

## 已经埋好的接口(无需改 player.html)
`player.html` 里的 `setMascot`/`mascotTalk` 已经在调:
```js
window.XLAvatar && window.XLAvatar.setState(state)   // idle/happy/listen/think/alarm/help/caring
window.XLAvatar && window.XLAvatar.setTalking(on)    // 说话开始/结束(驱动口型)
```
`live2d.js` 实现了 `window.XLAvatar`。你只要提供模型 + 表情名映射。

## 三步接入
1. **准备模型**:一套 Cubism 模型(`xxx.model3.json` + 贴图/物理/表情等)。
   - 测试可用官方免费样例(Hiyori / Haru / 桃濑日和 等),搜索 “Live2D sample model model3.json”。
   - ⚠ **授权**:Live2D Cubism 的运行时与样例模型受 Live2D Inc. 许可协议约束;商用需遵守其条款,
     商用形象建议自研或购买授权。别把他人模型直接商用。
2. **填地址 + 表情映射**:编辑 `live2d.js` 顶部 `CONFIG`:
   ```js
   MODEL_URL: 'models/hiyori/hiyori.model3.json',   // 你的模型
   EXPRESSIONS: { idle:'exp_idle', happy:'exp_smile', alarm:'exp_surprised', ... }
   ```
   表情名要对应模型里**真实的** expression 名(见下面「怎么查表情名」)。
3. **打开**:`player.html?live2d=1` —— 加载成功后 SVG 自动隐藏,Live2D 接管;
   高危来电时同样切到你映射的 `alarm` 表情。

## 怎么查表情名
- 打开模型的 `*.model3.json`,看 `FileReferences.Expressions[].Name`;或看 `exp3.json` 文件名。
- 把这些名字填进 `CONFIG.EXPRESSIONS`。动作组同理(`Motions` 里的组名填 `CONFIG.MOTIONS`)。
- 口型参数大多是 `ParamMouthOpenY`;个别模型不同,改 `CONFIG.MOUTH_PARAM`。

## 依赖与离线
默认从 CDN 拉取 Cubism Core / PIXI / pixi-live2d-display(**需联网**)。
要离线:把这三个 JS 下载到本地,改 `CONFIG.CDN` 为本地路径即可。

## 说明
- 这是**最佳努力的适配骨架**:不同模型的表情/参数命名不一,首次接入通常要按你的模型微调
  `EXPRESSIONS/MOTIONS/MOUTH_PARAM`。调不通不影响演示——会自动退回 SVG 小蜜蜂。
- 想要"表情随语音口型同步 + 更细腻情绪",Live2D(2D 骨骼)或 Spine 都可;
  3D 手办质感则需要 3D 模型 + three.js/Unity,工程量更大。
