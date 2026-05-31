package com.huawei.ascend.service.sessionmanage.redis;

import com.huawei.ascend.service.sessionmanage.model.Session;

public interface SessionCodec {
    String encode(Session session);

    Session decode(String value);
}
