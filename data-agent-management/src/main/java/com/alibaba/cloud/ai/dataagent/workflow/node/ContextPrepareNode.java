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
package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.dto.prompt.IntentRecognitionOutputDTO;
import com.alibaba.cloud.ai.dataagent.dto.prompt.QueryEnhanceOutputDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.ColumnDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.TableDTO;
import com.alibaba.cloud.ai.dataagent.entity.AgentDatasource;
import com.alibaba.cloud.ai.dataagent.entity.AgentKnowledge;
import com.alibaba.cloud.ai.dataagent.entity.LogicalRelation;
import com.alibaba.cloud.ai.dataagent.entity.SemanticModel;
import com.alibaba.cloud.ai.dataagent.mapper.AgentKnowledgeMapper;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.datasource.AgentDatasourceService;
import com.alibaba.cloud.ai.dataagent.service.datasource.DatasourceService;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.service.schema.SchemaService;
import com.alibaba.cloud.ai.dataagent.service.semantic.SemanticModelService;
import com.alibaba.cloud.ai.dataagent.util.DatabaseUtil;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.util.MarkdownParserUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;

/**
 * 上下文预处理节点 - 合并原意图识别、证据召回、查询增强、Schema召回、表关系分析、可行性评估
 * 适用于小规模数据场景（<10张表，少量知识库），直接全量加载所有上下文
 */
@Slf4j
@Component
@AllArgsConstructor
public class ContextPrepareNode implements NodeAction {

	private final SchemaService schemaService;

	private final AgentKnowledgeMapper agentKnowledgeMapper;

	private final AgentDatasourceService agentDatasourceService;

	private final DatabaseUtil databaseUtil;

	private final DatasourceService datasourceService;

	private final LlmService llmService;

	private final JsonParseUtil jsonParseUtil;

	private final SemanticModelService semanticModelService;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 1. 获取基本输入
		String userQuery = StateUtil.getStringValue(state, INPUT_KEY);
		String agentIdStr = StateUtil.getStringValue(state, AGENT_ID);
		String multiTurn = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "(无)");

		log.info("ContextPrepareNode start for agent: {}, query: {}", agentIdStr, userQuery);

		// 2. LLM 意图识别 - 闲聊/无关直接 END
		if (isChatOrIrrelevantByLlm(multiTurn, userQuery)) {
			log.info("User query is chat or irrelevant (LLM judged), ending flow");
			// 返回带消息的结果，直接返回文本消息
			String endMessage = "我收到了你的闲聊消息。如果你想查询数据，我可以帮你进行分析。请问有什么数据相关的问题吗？";
			
			// 创建流式返回
			Flux<ChatResponse> endFlux = Flux.just(ChatResponseUtil.createResponse(endMessage));
			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(
				this.getClass(), state, null, null,
				result -> {
					Map<String, Object> r = new HashMap<>();
					r.put(PREPARE_STATUS, "END");
					r.put(PREPARE_END_REASON, "CHAT_OR_IRRELEVANT");
					r.put(CONTEXT_PREPARE_OUTPUT, endMessage);
					r.put(FULL_CONTEXT, "");
					r.put(EVIDENCE, "");
					return r;
				},
				endFlux);
			
			return Map.of(CONTEXT_PREPARE_OUTPUT, generator);
		}

		// 3. 获取数据源配置
		Long agentId = Long.valueOf(agentIdStr);
		AgentDatasource agentDatasource = agentDatasourceService.getCurrentAgentDatasource(agentId);

		// 4. 检查数据源是否存在
		if (agentDatasource == null || agentDatasource.getDatasourceId() == null) {
			log.warn("Agent {} has no active datasource", agentId);
			return buildEndResult("NO_DATASOURCE");
		}

		Integer datasourceId = agentDatasource.getDatasourceId();

		// 5. 获取数据库方言
		DbConfigBO dbConfigBO = databaseUtil.getAgentDbConfig(agentId);
		String dialectType = dbConfigBO.getDialectType();

		// 6. 全量加载知识库（用于查询增强和证据召回）
		List<AgentKnowledge> allKnowledge = loadAllKnowledge(agentId);
		String evidence = buildEvidence(allKnowledge);

		// 7. LLM 查询增强 - 将用户自然语言转换为规范化查询 + 扩展查询
		QueryEnhanceOutputDTO queryEnhanceOutputDTO = enhanceQueryByLlm(multiTurn, userQuery, evidence);
		String canonicalQuery = queryEnhanceOutputDTO.getCanonicalQuery();
		log.info("Query enhanced: original='{}', canonical='{}'", userQuery, canonicalQuery);

		// 8. 用 canonicalQuery 召回表结构（替代原始 userQuery）
		List<Document> tableDocuments = schemaService.getTableDocumentsByDatasource(datasourceId, canonicalQuery);

		if (tableDocuments == null || tableDocuments.isEmpty()) {
			log.warn("No table documents found for datasource: {}", datasourceId);
			return buildEndResult("NO_TABLES");
		}

		// 提取表名
		List<String> tableNames = tableDocuments.stream()
			.map(doc -> (String) doc.getMetadata().get("name"))
			.filter(Objects::nonNull)
			.collect(Collectors.toList());

		// 9. 获取列信息
		List<Document> columnDocuments = schemaService.getColumnDocumentsByTableName(datasourceId, tableNames);

		// 10. 构建 SchemaDTO
		SchemaDTO schemaDTO = new SchemaDTO();
		schemaService.extractDatabaseName(schemaDTO, dbConfigBO);
		schemaService.buildSchemaFromDocuments(agentId.toString(), columnDocuments, tableDocuments, schemaDTO);

		// 11. 获取逻辑外键（表关系）
		List<LogicalRelation> logicalRelations = datasourceService.getLogicalRelations(datasourceId);
		List<String> foreignKeys = new ArrayList<>();
		if (logicalRelations != null) {
			for (LogicalRelation lr : logicalRelations) {
				if (tableNames.contains(lr.getSourceTableName()) || tableNames.contains(lr.getTargetTableName())) {
					foreignKeys.add(String.format("%s.%s=%s.%s", lr.getSourceTableName(), lr.getSourceColumnName(),
							lr.getTargetTableName(), lr.getTargetColumnName()));
				}
			}
		}
		if (!foreignKeys.isEmpty()) {
			List<String> existingFk = schemaDTO.getForeignKeys();
			if (existingFk == null) {
				schemaDTO.setForeignKeys(foreignKeys);
			}
			else {
				existingFk.addAll(foreignKeys);
			}
		}

		// 12. 获取语义模型（使用 SemanticModelService，与旧 TableRelationNode 一致）
		List<SemanticModel> semanticModels = semanticModelService.getByAgentIdAndTableNames(agentId, tableNames);
		String semanticModelPrompt = PromptHelper.buildSemanticModelPrompt(semanticModels);

		// 13. 构建完整上下文
		String fullContext = buildFullContext(canonicalQuery, schemaDTO, allKnowledge);

		// 14. 返回成功结果
		Map<String, Object> result = new HashMap<>();
		result.put(FULL_CONTEXT, fullContext);
		result.put(TABLE_RELATION_OUTPUT, schemaDTO);
		result.put(EVIDENCE, evidence);
		result.put(DB_DIALECT_TYPE, dialectType);
		result.put(GENEGRATED_SEMANTIC_MODEL_PROMPT, semanticModelPrompt);
		result.put(QUERY_ENHANCE_NODE_OUTPUT, queryEnhanceOutputDTO);
		result.put(PREPARE_STATUS, "SUCCESS");
		result.put(PREPARE_END_REASON, "");

		log.info("ContextPrepareNode completed successfully, tables: {}, knowledge: {}", tableNames.size(),
				allKnowledge.size());

		return result;
	}

	/**
	 * LLM 意图识别 - 替代简单关键词匹配
	 * 参考旧 IntentRecognitionNode.java
	 */
	private boolean isChatOrIrrelevantByLlm(String multiTurn, String userQuery) {
		try {
			String prompt = PromptHelper.buildIntentRecognitionPrompt(multiTurn, userQuery);
			String llmOutput = llmService.blockToString(llmService.callUser(prompt));
			if (llmOutput == null || llmOutput.trim().isEmpty()) {
				log.warn("LLM intent recognition returned empty, defaulting to data analysis request");
				return false;
			}
			String rawText = MarkdownParserUtil.extractRawText(llmOutput.trim());
			IntentRecognitionOutputDTO intent = jsonParseUtil.tryConvertToObject(rawText,
					IntentRecognitionOutputDTO.class);
			if (intent == null || intent.getClassification() == null) {
				log.warn("Failed to parse intent recognition result, defaulting to data analysis request");
				return false;
			}
			boolean isChat = "《闲聊或无关指令》".equals(intent.getClassification());
			log.info("Intent recognition result: {}, isChat={}", intent.getClassification(), isChat);
			return isChat;
		}
		catch (Exception e) {
			log.error("LLM intent recognition failed, defaulting to data analysis request", e);
			return false;
		}
	}

	/**
	 * LLM 查询增强 - 将用户自然语言转换为规范化查询 + 扩展查询
	 * 参考旧 QueryEnhanceNode.java
	 */
	private QueryEnhanceOutputDTO enhanceQueryByLlm(String multiTurn, String userQuery, String evidence) {
		try {
			String prompt = PromptHelper.buildQueryEnhancePrompt(multiTurn, userQuery, evidence);
			String llmOutput = llmService.blockToString(llmService.callUser(prompt));
			if (llmOutput != null && !llmOutput.trim().isEmpty()) {
				String rawText = MarkdownParserUtil.extractRawText(llmOutput.trim());
				QueryEnhanceOutputDTO result = jsonParseUtil.tryConvertToObject(rawText, QueryEnhanceOutputDTO.class);
				if (result != null && result.getCanonicalQuery() != null
						&& !result.getCanonicalQuery().trim().isEmpty()) {
					log.info("Query enhancement succeeded: {}", result.getCanonicalQuery());
					return result;
				}
			}
		}
		catch (Exception e) {
			log.error("LLM query enhancement failed, using original query as fallback", e);
		}
		// 降级：使用原始查询
		log.warn("Query enhancement failed or returned empty, using original query as fallback");
		QueryEnhanceOutputDTO fallback = new QueryEnhanceOutputDTO();
		fallback.setCanonicalQuery(userQuery);
		fallback.setExpandedQueries(Collections.singletonList(userQuery));
		return fallback;
	}

	/**
	 * 加载指定 Agent 的所有知识库（未删除的）
	 */
	private List<AgentKnowledge> loadAllKnowledge(Long agentId) {
		// 查询所有 is_recall=1 且未删除的知识
		List<Integer> knowledgeIds = agentKnowledgeMapper.selectRecalledKnowledgeIds(agentId.intValue());
		if (knowledgeIds == null || knowledgeIds.isEmpty()) {
			return Collections.emptyList();
		}
		List<AgentKnowledge> knowledgeList = new ArrayList<>();
		for (Integer id : knowledgeIds) {
			AgentKnowledge knowledge = agentKnowledgeMapper.selectById(id);
			if (knowledge != null) {
				knowledgeList.add(knowledge);
			}
		}
		return knowledgeList;
	}

	/**
	 * 构建完整上下文字符串
	 */
	private String buildFullContext(String canonicalQuery, SchemaDTO schemaDTO, List<AgentKnowledge> knowledgeList) {
		StringBuilder sb = new StringBuilder();
		sb.append("用户问题：").append(canonicalQuery).append("\n\n");

		// 表结构
		sb.append("数据库表结构：\n");
		if (schemaDTO.getTable() != null) {
			for (TableDTO table : schemaDTO.getTable()) {
				sb.append("- ").append(table.getName());
				if (table.getDescription() != null) {
					sb.append(": ").append(table.getDescription());
				}
				sb.append("\n");
				if (table.getColumn() != null) {
					for (ColumnDTO col : table.getColumn()) {
						sb.append("  - ").append(col.getName()).append(" (").append(col.getType()).append(")");
						if (col.getDescription() != null) {
							sb.append(": ").append(col.getDescription());
						}
						sb.append("\n");
					}
				}
			}
		}

		// 外键关系
		if (schemaDTO.getForeignKeys() != null && !schemaDTO.getForeignKeys().isEmpty()) {
			sb.append("\n表关系：\n");
			for (String fk : schemaDTO.getForeignKeys()) {
				sb.append("- ").append(fk).append("\n");
			}
		}

		// 知识库
		if (knowledgeList != null && !knowledgeList.isEmpty()) {
			sb.append("\n业务知识：\n");
			for (AgentKnowledge knowledge : knowledgeList) {
				sb.append("- ").append(knowledge.getTitle()).append(": ").append(knowledge.getContent()).append("\n");
			}
		}

		return sb.toString();
	}

	/**
	 * 构建证据字符串（给 PlannerNode 使用）
	 */
	private String buildEvidence(List<AgentKnowledge> knowledgeList) {
		if (knowledgeList == null || knowledgeList.isEmpty()) {
			return "无";
		}
		StringBuilder sb = new StringBuilder();
		for (AgentKnowledge knowledge : knowledgeList) {
			sb.append("- [").append(knowledge.getTitle()).append("] ").append(knowledge.getContent()).append("\n");
		}
		return sb.toString();
	}

	/**
	 * 构建结束结果
	 */
	private Map<String, Object> buildEndResult(String reason) {
		String displayMessage;
		switch (reason) {
			case "CHAT_OR_IRRELEVANT":
				displayMessage = "我收到了你的闲聊消息。如果你想查询数据，我可以帮你进行分析。请问有什么数据相关的问题吗？";
				break;
			case "NO_DATASOURCE":
				displayMessage = "抱歉，该Agent未配置数据源，无法查询数据。请先配置数据源后再试。";
				break;
			case "NO_TABLES":
				displayMessage = "抱歉，未找到相关的数据表。请检查数据源配置或尝试其他问题。";
				break;
			default:
				displayMessage = "抱歉，当前无法处理你的请求。请稍后重试。";
		}
		
		log.info("ContextPrepareNode ending with reason: {}, message: {}", reason, displayMessage);
		
		// 返回带消息的结果，使用 CHAT_MESSAGE 键让前端显示
		Map<String, Object> result = new HashMap<>();
		result.put(PREPARE_STATUS, "END");
		result.put(PREPARE_END_REASON, reason);
		result.put("CHAT_MESSAGE", displayMessage);
		result.put(FULL_CONTEXT, "");
		result.put(EVIDENCE, "");
		return result;
	}

}
