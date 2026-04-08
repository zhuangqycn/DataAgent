/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.workflow.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PREPARE_STATUS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.CONTEXT_PREPARE_OUTPUT;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 根据上下文预处理结果决定下一个节点的分发器
 */
@Slf4j
public class ContextPrepareDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) throws Exception {
		// 获取预处理状态
		String prepareStatus = StateUtil.getStringValue(state, PREPARE_STATUS, "");

		log.info("ContextPrepareDispatcher: status = {}", prepareStatus);

		if ("SUCCESS".equals(prepareStatus)) {
			log.info("ContextPrepare success, proceeding to PLANNER_NODE");
			return PLANNER_NODE;
		}
		else {
			// 其他情况（END 或空）都结束流程
			String endReason = StateUtil.getStringValue(state, "PREPARE_END_REASON", "UNKNOWN");
			
			// 获取终端消息（如果有的话）
			String endMessage = StateUtil.getStringValue(state, CONTEXT_PREPARE_OUTPUT, "");
			if (endMessage != null && !endMessage.isEmpty()) {
				log.info("ContextPrepare ended with reason: {}, message: {}, routing to END", endReason, endMessage);
			} else {
				log.info("ContextPrepare ended with reason: {}, routing to END", endReason);
			}
			return END;
		}
	}

}
