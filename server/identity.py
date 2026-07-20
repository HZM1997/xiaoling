"""实名核验适配器。

默认只做身份证格式与校验位检查,不会把“格式正确”冒充“实名成功”。生产环境通过
REAL_NAME_VERIFY_URL 接合法实名服务;开发环境可显式设置 REAL_NAME_DEMO=true。
"""
from __future__ import annotations

import json
import os
import re
import urllib.request

_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
_CHECK = "10X98765432"


def valid_chinese_id(id_no: str) -> bool:
    value = id_no.strip().upper()
    if not re.fullmatch(r"\d{17}[0-9X]", value):
        return False
    total = sum(int(value[i]) * _WEIGHTS[i] for i in range(17))
    return _CHECK[total % 11] == value[-1]


def verify(name: str, id_no: str) -> tuple[bool, str]:
    name = name.strip()
    id_no = id_no.strip().upper()
    if not re.fullmatch(r"[\u3400-\u9fff·]{2,30}", name):
        return False, "请输入与证件一致的姓名"
    if not valid_chinese_id(id_no):
        return False, "身份证号码格式或校验位不正确"

    endpoint = os.getenv("REAL_NAME_VERIFY_URL", "").strip()
    if endpoint:
        body = json.dumps({"name": name, "id_no": id_no}, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        secret = os.getenv("REAL_NAME_VERIFY_TOKEN", "").strip()
        if secret:
            headers["Authorization"] = "Bearer " + secret
        try:
            request = urllib.request.Request(endpoint, data=body, headers=headers, method="POST")
            with urllib.request.urlopen(request, timeout=4.0) as response:
                result = json.loads(response.read().decode("utf-8"))
            return (bool(result.get("verified")),
                    str(result.get("msg", "认证信息不匹配" if not result.get("verified") else "")))
        except Exception:
            return False, "实名服务暂时不可用,请稍后再试"

    if os.getenv("REAL_NAME_DEMO", "").lower() in {"1", "true", "yes"}:
        return True, ""
    return False, "实名核验服务尚未配置,暂时不能完成认证"
