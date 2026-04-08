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
package com.alibaba.cloud.ai.dataagent.service.llm.impls;

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
@AllArgsConstructor
public class StreamLlmService implements LlmService {

	private final AiModelRegistry registry;

	@Override
	public Flux<ChatResponse> call(String system, String user) {
		log.info("========== StreamLlmService.call 开始调用LLM ==========");
		log.info("System prompt长度: {}, User prompt长度: {}", 
			system != null ? system.length() : 0, user != null ? user.length() : 0);
		return Mono
			.fromCallable(() -> registry.getChatClient().prompt().system(system).user(user))
			.map(promptSpec -> promptSpec.stream().chatResponse())
			.flatMapMany(responseFlux -> responseFlux)
			.retryWhen(getRetrySpec())
			.doOnSubscribe(subscription -> log.info("========== LLM订阅开始 =========="))
			.doOnNext(response -> log.info("========== LLM返回内容: {} ==========", 
				response.getResult() != null && response.getResult().getOutput() != null 
				? response.getResult().getOutput().getText().substring(0, Math.min(200, 
					response.getResult().getOutput().getText().length())) : "null"))
			.doOnError(error -> log.error("========== LLM 调用失败: {} ==========", error.getMessage(), error));
	}

	@Override
	public Flux<ChatResponse> callSystem(String system) {
		log.info("========== StreamLlmService.callSystem 开始调用LLM ==========");
		log.info("System prompt长度: {}", system != null ? system.length() : 0);
		return Mono
			.fromCallable(() -> registry.getChatClient().prompt().system(system))
			.map(promptSpec -> promptSpec.stream().chatResponse())
			.flatMapMany(responseFlux -> responseFlux)
			.retryWhen(getRetrySpec())
			.doOnSubscribe(subscription -> log.info("========== LLM订阅开始 =========="))
			.doOnNext(response -> log.info("========== LLM返回内容: {} ==========", 
				response.getResult() != null && response.getResult().getOutput() != null 
				? response.getResult().getOutput().getText().substring(0, Math.min(200, 
					response.getResult().getOutput().getText().length())) : "null"))
			.doOnError(error -> log.error("========== LLM 调用失败: {} ==========", error.getMessage(), error));
	}

	@Override
	public Flux<ChatResponse> callUser(String user) {
		log.info("========== StreamLlmService.callUser 开始调用LLM ==========");
		log.info("User prompt长度: {}", user != null ? user.length() : 0);
		return Mono
			.fromCallable(() -> registry.getChatClient().prompt().user(user))
			.map(promptSpec -> promptSpec.stream().chatResponse())
			.flatMapMany(responseFlux -> responseFlux)
			.retryWhen(getRetrySpec())
			.doOnSubscribe(subscription -> log.info("========== LLM订阅开始 =========="))
			.doOnNext(response -> log.info("========== LLM返回内容: {} ==========", 
				response.getResult() != null && response.getResult().getOutput() != null 
				? response.getResult().getOutput().getText().substring(0, Math.min(200, 
					response.getResult().getOutput().getText().length())) : "null"))
			.doOnError(error -> log.error("========== LLM 调用失败: {} ==========", error.getMessage(), error));
	}

	/**
	 * 获取重试策略配置（从系统属性读取）
	 */
	private Retry getRetrySpec() {
		// 从系统属性读取配置，支持动态调整
		int maxRetries = Integer.getInteger("llm.retry.maxAttempts", 5);
		long initialBackoffMillis = Long.getLong("llm.retry.initialBackoffMillis", 1000L);
		long maxBackoffMillis = Long.getLong("llm.retry.maxBackoffMillis", 30000L);

		log.info("LLM 重试策略：最大重试次数={}, 初始延迟={}ms, 最大延迟={}ms",
				maxRetries, initialBackoffMillis, maxBackoffMillis);

		return Retry.backoff(maxRetries, Duration.ofMillis(initialBackoffMillis))
			.maxBackoff(Duration.ofMillis(maxBackoffMillis))
			.onRetryExhaustedThrow((spec, signal) -> {
				log.error("调用 LLM 失败，已达最大重试次数 {} 次：{}", maxRetries, signal.failure().getMessage());
				return signal.failure();
			});
	}

}
