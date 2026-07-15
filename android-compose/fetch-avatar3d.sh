#!/usr/bin/env bash
# 把 3D 数字人运行时 + 一个免费样例 VRM 下载到 assets/,让 3D 形象离线可用。
# 用法:cd android-compose && bash fetch-avatar3d.sh
# 之后把 assets/avatar3d/index.html 的 importmap 与 SAMPLE 改成本地路径(见 NATIVE.md)。
set -e
DIR="app/src/main/assets/avatar3d"
LIB="$DIR/lib"
mkdir -p "$LIB"

echo "下载 three.js 运行时…"
curl -fL "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js" -o "$LIB/three.module.js"
curl -fL "https://cdn.jsdelivr.net/npm/three@0.160.0/examples/jsm/loaders/GLTFLoader.js" -o "$LIB/GLTFLoader.js"
curl -fL "https://cdn.jsdelivr.net/npm/@pixiv/three-vrm@3.1.4/lib/three-vrm.module.min.js" -o "$LIB/three-vrm.module.js"

echo "下载免费样例 VRM(仅测试;商用请换授权模型)…"
curl -fL "https://cdn.jsdelivr.net/gh/pixiv/three-vrm@dev/packages/three-vrm/examples/models/VRM1_Constraint_Twist_Sample.vrm" -o "$DIR/model.vrm" \
  || echo "样例下载失败,请自备 model.vrm 放到 $DIR/"

echo "完成。产物:"
ls -la "$LIB" "$DIR"/*.vrm 2>/dev/null || true
echo ""
echo "离线化:把 index.html 的 importmap 改成:"
echo '  "three":"./lib/three.module.js", "three/addons/":"./lib/", "@pixiv/three-vrm":"./lib/three-vrm.module.js"'
echo "并把 GLTFLoader 引入路径改成 ./lib/GLTFLoader.js"
