package com.huawei.ascend.service.engine.adapter.openjiuwen;

import com.huawei.ascend.service.engine.handler.AgentExecutionContext;
import com.huawei.ascend.service.engine.model.EngineMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an {@link AgentExecutionContext} into the input map openJiuwen's
 * {@code Runner.runAgent} expects. First version handles text messages only,
 * keying the last user message as {@code query} and the task id as
 * {@code conversation_id}. See engine model design §10.3.
 */
public class OpenJiuwenMessageConverter {

    public Object toOpenJiuwenInput(AgentExecutionContext context) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", lastUserText(context));
        input.put("conversation_id", context.getScope().taskId());
        return input;
    }

    private String lastUserText(AgentExecutionContext context) {
        List<EngineMessage> messages = context.getInput() == null ? null : context.getInput().messages();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            EngineMessage message = messages.get(i);
            if (message != null && "user".equals(message.role())) {
                return message.content();
            }
        }
        return messages.get(messages.size() - 1).content();
    }
}
