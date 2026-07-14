#!/usr/bin/env python3
"""
小灵 · 命令行"语音回合"模拟器
不用安卓设备就能演示完整闭环:输入=模拟用户说的话,输出=精灵播报 + 要执行的动作。
用法:
  cd demo && python cli_demo.py            # 交互模式,直接打字模拟说话
  cd demo && python cli_demo.py --scenario # 一键跑内置演示剧本(防诈/呼救/打电话/导航)
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))

from main import handle  # noqa: E402


def show(reply, label=""):
    tag = f"[{reply.skill}]" if reply.skill else ""
    print(f"  🔊 小灵{tag}: {reply.speech}")
    if reply.action:
        print(f"  ⚙️  动作: {reply.action}")
    if reply.risk:
        bar = "█" * int(reply.risk * 10)
        print(f"  ⚠️  风险分: {reply.risk:.2f} {bar}")
    print()


SCRIPT = [
    ("『喂?』(接到陌生来电,对方开口)",
     "您好,我是公检法的,您涉嫌洗钱,需要把钱转到安全账户配合调查",
     {"scene": "incoming_call", "caller": "+81 90xxxx"}),
    ("『客服说要退款』",
     "亲,您的快递丢了要理赔,请打开屏幕共享,念一下收到的验证码",
     {"scene": "incoming_call", "caller": "0085212345"}),
    ("老人突然说", "哎哟我胸口疼,喘不上气,快不行了", None),
    ("老人对精灵说", "打电话给女儿", None),
    ("老人对精灵说", "导航到人民医院", None),
    ("老人对精灵说", "提醒我每天早上八点吃药", None),
    ("老人对精灵说", "放一段京剧", None),
    ("老人闲聊", "今天天气真好,有点想老伴了", None),
]


def run_scenario():
    print("=" * 60)
    print("  小灵 · 演示剧本(防诈 / 呼救 / 打电话 / 导航 / 陪伴)")
    print("=" * 60, "\n")
    for label, text, ctx in SCRIPT:
        print(f"👵 {label}")
        print(f"  🗣️  用户: {text}")
        show(handle(text, context=ctx))


def run_interactive():
    print("小灵已就绪。直接打字模拟『对精灵说的话』,输入 q 退出。")
    print("(想模拟来电诈骗,输入以 call: 开头,例如: call:您涉嫌洗钱请转账到安全账户)\n")
    while True:
        try:
            text = input("🗣️  你: ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if text in ("q", "quit", "exit"):
            break
        if not text:
            continue
        if text.startswith("call:"):
            show(handle(text[5:], context={"scene": "incoming_call", "caller": "0085200000"}))
        else:
            show(handle(text))


if __name__ == "__main__":
    if "--scenario" in sys.argv:
        run_scenario()
    else:
        run_interactive()
